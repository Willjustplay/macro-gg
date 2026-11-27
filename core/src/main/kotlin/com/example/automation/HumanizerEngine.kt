package com.example.automation

import com.example.data.HumanizerConfig
import com.example.data.HumanizerPreset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/** Output of [HumanizerEngine.humanizeClick]. */
data class HumanizedClick(
    val x: Int,
    val y: Int,
    val durationMs: Long,
    val humanized: Boolean
)

/** Output of [HumanizerEngine.humanizeSwipe]: quadratic Bezier path `(x1,y1) -> (cx,cy) -> (x2,y2)`. */
data class HumanizedSwipe(
    val x1: Int,
    val y1: Int,
    val cx: Int,
    val cy: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Long,
    val curved: Boolean
)

/**
 * Pure-Kotlin anti-detection Humanizer math engine:
 * - Box-Muller Gaussian tap clustering (denser near target center, smoothly clamped within radius).
 * - Quadratic Bezier curved swipe control points mimicking natural thumb arcs.
 * - Organic timing variance, tap hold micro-variation, occasional micro-hesitations, and Human Break intervals.
 */
class HumanizerEngine(
    private val random: Random = Random.Default
) {

    /** Box-Muller standard normal sample N(0, 1). */
    fun nextGaussian(): Double {
        var u1 = random.nextDouble()
        if (u1 < 1e-9) u1 = 1e-9
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /**
     * Applies Gaussian humanizer tap scatter (or legacy uniform [legacyJitterPx] when Humanizer is OFF)
     * and natural tap hold duration variation (for normal clicks, preserving intentional long presses).
     */
    fun humanizeClick(
        x: Int,
        y: Int,
        baseDurationMs: Long,
        screenW: Int,
        screenH: Int,
        legacyJitterPx: Int,
        rawConfig: HumanizerConfig,
        isLongPress: Boolean = false
    ): HumanizedClick {
        val cfg = rawConfig.effective()
        val maxX = (screenW - 2).coerceAtLeast(1)
        val maxY = (screenH - 2).coerceAtLeast(1)

        if (cfg.preset == HumanizerPreset.OFF) {
            val j = legacyJitterPx.coerceAtLeast(0)
            val tx = if (j > 0) (x + random.nextInt(-j, j + 1)).coerceIn(1, maxX) else x.coerceIn(1, maxX)
            val ty = if (j > 0) (y + random.nextInt(-j, j + 1)).coerceIn(1, maxY) else y.coerceIn(1, maxY)
            return HumanizedClick(tx, ty, baseDurationMs.coerceAtLeast(10L), humanized = false)
        }

        val radius = maxOf(cfg.gaussianClickRadiusPx, legacyJitterPx).coerceIn(0, 80)
        val (dx, dy) = if (radius > 0) {
            val sigma = radius / 2.2
            val gx = (nextGaussian() * sigma).roundToInt().coerceIn(-radius, radius)
            val gy = (nextGaussian() * sigma).roundToInt().coerceIn(-radius, radius)
            gx to gy
        } else 0 to 0

        val tx = (x + dx).coerceIn(1, maxX)
        val ty = (y + dy).coerceIn(1, maxY)

        val dur = if (isLongPress) {
            val variance = (baseDurationMs * (cfg.delayVariancePercent.coerceIn(0, 30) / 200.0)).roundToLong()
            if (variance > 0L) (baseDurationMs + random.nextLong(-variance, variance + 1L)).coerceAtLeast(250L)
            else baseDurationMs.coerceAtLeast(250L)
        } else {
            val minD = cfg.tapDurationMinMs.coerceIn(15L, 250L)
            val maxD = cfg.tapDurationMaxMs.coerceIn(minD, 350L)
            if (baseDurationMs in 15L..220L && maxD > minD) {
                random.nextLong(minD, maxD + 1L)
            } else {
                baseDurationMs.coerceAtLeast(15L)
            }
        }

        return HumanizedClick(tx, ty, dur, humanized = true)
    }

    /**
     * Generates a slightly curved quadratic Bezier path `(x1,y1) -> (cx,cy) -> (x2,y2)` with subtle
     * start/end scatter and duration jitter when Humanizer Bezier swipe is enabled.
     */
    fun humanizeSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        baseDurationMs: Long,
        screenW: Int,
        screenH: Int,
        rawConfig: HumanizerConfig
    ): HumanizedSwipe {
        val cfg = rawConfig.effective()
        val maxX = (screenW - 1).coerceAtLeast(1)
        val maxY = (screenH - 1).coerceAtLeast(1)
        val sx1 = x1.coerceIn(0, maxX)
        val sy1 = y1.coerceIn(0, maxY)
        val sx2 = x2.coerceIn(0, maxX)
        val sy2 = y2.coerceIn(0, maxY)

        if (cfg.preset == HumanizerPreset.OFF || !cfg.bezierSwipeEnabled) {
            return HumanizedSwipe(
                x1 = sx1,
                y1 = sy1,
                cx = (sx1 + sx2) / 2,
                cy = (sy1 + sy2) / 2,
                x2 = sx2,
                y2 = sy2,
                durationMs = baseDurationMs.coerceAtLeast(30L),
                curved = false
            )
        }

        val endJitter = (cfg.gaussianClickRadiusPx / 2).coerceIn(0, 12)
        val hx1 = if (endJitter > 0) (sx1 + random.nextInt(-endJitter, endJitter + 1)).coerceIn(0, maxX) else sx1
        val hy1 = if (endJitter > 0) (sy1 + random.nextInt(-endJitter, endJitter + 1)).coerceIn(0, maxY) else sy1
        val hx2 = if (endJitter > 0) (sx2 + random.nextInt(-endJitter, endJitter + 1)).coerceIn(0, maxX) else sx2
        val hy2 = if (endJitter > 0) (sy2 + random.nextInt(-endJitter, endJitter + 1)).coerceIn(0, maxY) else sy2

        val dx = (hx2 - hx1).toDouble()
        val dy = (hy2 - hy1).toDouble()
        val dist = hypot(dx, dy)
        if (dist < 18.0) {
            return HumanizedSwipe(hx1, hy1, (hx1 + hx2) / 2, (hy1 + hy2) / 2, hx2, hy2, baseDurationMs.coerceAtLeast(30L), curved = false)
        }

        // Perpendicular unit normal
        val nx = -dy / dist
        val ny = dx / dist
        val sign = if (random.nextBoolean()) 1.0 else -1.0
        val factor = cfg.bezierCurveFactor.coerceIn(0.03f, 0.35f).toDouble()
        // Natural deflection between 50%..115% of curveFactor, capped reasonably in pixels
        val arcMagnitude = (dist * factor * (0.50 + random.nextDouble() * 0.65)).coerceAtMost(180.0)
        // Slight asymmetry along the stroke (0.38..0.62) like a real thumb arc
        val t = 0.38 + random.nextDouble() * 0.24
        val midX = hx1 + dx * t
        val midY = hy1 + dy * t
        val cx = (midX + nx * sign * arcMagnitude).roundToInt().coerceIn(0, maxX)
        val cy = (midY + ny * sign * arcMagnitude).roundToInt().coerceIn(0, maxY)

        val varPct = cfg.delayVariancePercent.coerceIn(0, 40) / 100.0
        val durMult = 1.0 + (random.nextDouble() * 2.0 - 1.0) * (varPct * 0.65)
        val dur = (baseDurationMs * durMult).roundToLong().coerceAtLeast(35L)

        return HumanizedSwipe(hx1, hy1, cx, cy, hx2, hy2, dur, curved = true)
    }

    /** Computes humanized delay combining legacy [legacyRandomDelayMs], natural % variance, and rare micro-hesitation. */
    fun humanizeDelay(
        baseMs: Long,
        legacyRandomDelayMs: Long,
        rawConfig: HumanizerConfig
    ): Long {
        val legacyExtra = if (legacyRandomDelayMs > 0L) random.nextLong(0L, legacyRandomDelayMs + 1L) else 0L
        val cfg = rawConfig.effective()
        if (cfg.preset == HumanizerPreset.OFF) {
            return (baseMs + legacyExtra).coerceAtLeast(0L)
        }
        val baseTotal = (baseMs + legacyExtra).coerceAtLeast(0L)
        val varPct = cfg.delayVariancePercent.coerceIn(0, 50)
        val varied = if (baseTotal > 0L && varPct > 0) {
            val delta = (baseTotal * (varPct / 100.0) * nextGaussian() * 0.55).roundToLong()
            val maxShift = (baseTotal * varPct) / 100L
            (baseTotal + delta.coerceIn(-maxShift, maxShift)).coerceAtLeast(15L)
        } else {
            baseTotal
        }
        val hesitation = if (cfg.microHesitationChancePercent > 0 &&
            random.nextInt(100) < cfg.microHesitationChancePercent.coerceIn(0, 40)
        ) {
            random.nextLong(140L, 381L)
        } else {
            0L
        }
        return (varied + hesitation).coerceAtLeast(0L)
    }

    /** Picks next interval (ms) until a Human Break triggers, or 0L if disabled. */
    fun nextBreakIntervalMs(rawConfig: HumanizerConfig): Long {
        val cfg = rawConfig.effective()
        if (!cfg.humanBreakEnabled) return 0L
        val minMin = cfg.breakEveryMinMinutes.coerceIn(1, 360)
        val maxMin = cfg.breakEveryMaxMinutes.coerceIn(minMin, 480)
        val chosenSec = random.nextLong(minMin * 60L, maxMin * 60L + 1L)
        return chosenSec * 1000L
    }

    /** Picks duration (ms) for a Human Break rest. */
    fun nextBreakDurationMs(rawConfig: HumanizerConfig): Long {
        val cfg = rawConfig.effective()
        if (!cfg.humanBreakEnabled) return 0L
        val minSec = cfg.breakDurationMinSec.coerceIn(5, 3600)
        val maxSec = cfg.breakDurationMaxSec.coerceIn(minSec, 7200)
        return random.nextLong(minSec.toLong(), maxSec.toLong() + 1L) * 1000L
    }
}
