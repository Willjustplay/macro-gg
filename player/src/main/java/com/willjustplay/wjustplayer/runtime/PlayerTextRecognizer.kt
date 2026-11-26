package com.willjustplay.wjustplayer.runtime

import com.example.vision.OcrBlock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.example.data.Region
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 100% offline on-device OCR wrapper using ML Kit bundled Latin/digits Text Recognition.
 *
 * Game-HUD optimizations:
 * 1. Adaptive upscaling based on the shorter side (handles wide narrow HUD strips like 500x45 gold counters).
 * 2. Quiet-zone padding around tightly cropped regions so border-touching digits are never dropped by ML Kit.
 * 3. Multi-pass contrast extraction when [enhanced] is true (or Pass 1 returns empty):
 *    - Bright-text-on-dark extraction via max(R,G,B) + Otsu -> crisp black text on pure white (for outlined
 *      white/gold/yellow/green/pink game numbers on busy terrain).
 *    - Dark-text-on-light extraction via luminance + Otsu -> crisp black text on pure white.
 */
object PlayerTextRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(
        fullScreenshot: Bitmap,
        region: Region?,
        enhanced: Boolean = false
    ): List<OcrBlock> = withContext(Dispatchers.Default) {
        val cropX: Int
        val cropY: Int
        val cropW: Int
        val cropH: Int
        if (region != null && region.width >= 8 && region.height >= 8) {
            cropX = region.left.coerceIn(0, (fullScreenshot.width - 1).coerceAtLeast(0))
            cropY = region.top.coerceIn(0, (fullScreenshot.height - 1).coerceAtLeast(0))
            cropW = region.width.coerceAtMost(fullScreenshot.width - cropX).coerceAtLeast(1)
            cropH = region.height.coerceAtMost(fullScreenshot.height - cropY).coerceAtLeast(1)
        } else {
            cropX = 0
            cropY = 0
            cropW = fullScreenshot.width
            cropH = fullScreenshot.height
        }

        val isCropped = !(cropX == 0 && cropY == 0 && cropW == fullScreenshot.width && cropH == fullScreenshot.height)
        var cropped: Bitmap? = null
        try {
            val source = if (!isCropped) {
                fullScreenshot
            } else {
                Bitmap.createBitmap(fullScreenshot, cropX, cropY, cropW, cropH).also { cropped = it }
            }

            val scale = computeAdaptiveScale(source.width, source.height, isCropped)
            val pad = if (isCropped) computeQuietPad(source.width, source.height, scale) else 0

            if (!enhanced) {
                val rawBlocks = runRgbPass(source, scale, pad, cropX, cropY, cropW, cropH)
                if (rawBlocks.isNotEmpty()) return@withContext rawBlocks
                // Automatic game-HUD contrast fallback when raw RGB returned nothing
                val brightBlocks = runBinarizedPass(source, scale, pad, cropX, cropY, cropW, cropH, brightOnDark = true)
                if (brightBlocks.isNotEmpty()) return@withContext brightBlocks
                return@withContext runBinarizedPass(source, scale, pad, cropX, cropY, cropW, cropH, brightOnDark = false)
            } else {
                val collected = ArrayList<OcrBlock>()
                collected += runBinarizedPass(source, scale, pad, cropX, cropY, cropW, cropH, brightOnDark = true)
                collected += runBinarizedPass(source, scale, pad, cropX, cropY, cropW, cropH, brightOnDark = false)
                deduplicateBlocks(collected)
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            cropped?.recycle()
        }
    }

    /** Computes upscale factor so tiny HUD numbers (even wide 500×40 strips) reach crisp ML Kit glyph height. */
    internal fun computeAdaptiveScale(width: Int, height: Int, isCropped: Boolean): Float {
        if (!isCropped) return 1f
        val shortSide = min(width, height).coerceAtLeast(1)
        val longSide = max(width, height).coerceAtLeast(1)
        val desired = when {
            shortSide < 48 -> 3.5f
            shortSide < 85 -> 3.0f
            shortSide < 140 -> 2.25f
            shortSide < 240 -> 1.75f
            else -> 1f
        }
        val maxAllowed = (1800f / longSide.toFloat()).coerceAtLeast(1f)
        return min(desired, maxAllowed)
    }

    private fun computeQuietPad(width: Int, height: Int, scale: Float): Int {
        val scaledMin = (min(width, height) * scale).roundToInt()
        return (scaledMin / 4).coerceIn(20, 48)
    }

    private suspend fun runRgbPass(
        source: Bitmap,
        scale: Float,
        pad: Int,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int
    ): List<OcrBlock> {
        var prepared: Bitmap? = null
        return try {
            val targetW = (source.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (source.height * scale).roundToInt().coerceAtLeast(1)
            val inputBmp = if (scale > 1.01f || pad > 0) {
                val out = Bitmap.createBitmap(targetW + pad * 2, targetH + pad * 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawColor(sampleBorderColor(source))
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(
                    source,
                    null,
                    Rect(pad, pad, pad + targetW, pad + targetH),
                    paint
                )
                prepared = out
                out
            } else {
                source
            }
            recognizeBitmap(inputBmp, scale, pad, cropX, cropY, cropW, cropH)
        } finally {
            prepared?.recycle()
        }
    }

    private suspend fun runBinarizedPass(
        source: Bitmap,
        scale: Float,
        pad: Int,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int,
        brightOnDark: Boolean
    ): List<OcrBlock> {
        var scaledBmp: Bitmap? = null
        var binaryBmp: Bitmap? = null
        return try {
            val targetW = (source.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (source.height * scale).roundToInt().coerceAtLeast(1)
            val work = if (scale > 1.01f) {
                Bitmap.createScaledBitmap(source, targetW, targetH, true).also { scaledBmp = it }
            } else {
                source
            }
            val w = work.width
            val h = work.height
            val n = w * h
            val pixels = IntArray(n)
            work.getPixels(pixels, 0, w, 0, 0, w, h)

            val metric = IntArray(n)
            val hist = IntArray(256)
            if (brightOnDark) {
                // max(R, G, B) preserves bright white, yellow, gold, green, cyan, and pink game HUD text equally!
                for (i in 0 until n) {
                    val p = pixels[i]
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    val v = max(r, max(g, b))
                    metric[i] = v
                    hist[v]++
                }
            } else {
                for (i in 0 until n) {
                    val p = pixels[i]
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    val y = (77 * r + 150 * g + 29 * b) ushr 8
                    metric[i] = y
                    hist[y]++
                }
            }

            val rawThresh = computeOtsuThreshold(hist, n)
            val thresh = if (brightOnDark) rawThresh.coerceIn(135, 225) else rawThresh.coerceIn(55, 175)

            val outPad = max(pad, 24)
            val outW = w + outPad * 2
            val outH = h + outPad * 2
            val outPixels = IntArray(outW * outH) { Color.WHITE }
            for (y in 0 until h) {
                val srcRow = y * w
                val dstRow = (y + outPad) * outW + outPad
                if (brightOnDark) {
                    for (x in 0 until w) {
                        if (metric[srcRow + x] >= thresh) {
                            outPixels[dstRow + x] = Color.BLACK
                        }
                    }
                } else {
                    for (x in 0 until w) {
                        if (metric[srcRow + x] <= thresh) {
                            outPixels[dstRow + x] = Color.BLACK
                        }
                    }
                }
            }
            val out = Bitmap.createBitmap(outPixels, outW, outH, Bitmap.Config.ARGB_8888).also { binaryBmp = it }
            recognizeBitmap(out, scale, outPad, cropX, cropY, cropW, cropH)
        } finally {
            binaryBmp?.recycle()
            scaledBmp?.recycle()
        }
    }

    private fun sampleBorderColor(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return Color.BLACK
        val samples = listOf(
            bmp.getPixel(0, 0),
            bmp.getPixel(w - 1, 0),
            bmp.getPixel(0, h - 1),
            bmp.getPixel(w - 1, h - 1),
            bmp.getPixel(w / 2, 0),
            bmp.getPixel(w / 2, h - 1)
        )
        var rSum = 0
        var gSum = 0
        var bSum = 0
        for (p in samples) {
            rSum += (p ushr 16) and 0xFF
            gSum += (p ushr 8) and 0xFF
            bSum += p and 0xFF
        }
        return Color.rgb(rSum / samples.size, gSum / samples.size, bSum / samples.size)
    }

    private fun computeOtsuThreshold(hist: IntArray, total: Int): Int {
        if (total <= 0) return 128
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()
        var sumBg = 0.0
        var weightBg = 0
        var bestVar = -1.0
        var bestT = 128
        for (t in 0..255) {
            weightBg += hist[t]
            if (weightBg == 0) continue
            val weightFg = total - weightBg
            if (weightFg == 0) break
            sumBg += t * hist[t].toDouble()
            val meanBg = sumBg / weightBg
            val meanFg = (sumAll - sumBg) / weightFg
            val diff = meanBg - meanFg
            val betweenVar = weightBg.toDouble() * weightFg.toDouble() * diff * diff
            if (betweenVar > bestVar) {
                bestVar = betweenVar
                bestT = t
            }
        }
        return bestT
    }

    private suspend fun recognizeBitmap(
        inputBmp: Bitmap,
        scale: Float,
        pad: Int,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int
    ): List<OcrBlock> {
        val image = InputImage.fromBitmap(inputBmp, 0)
        val visionText = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        } ?: return emptyList()

        val blocks = ArrayList<OcrBlock>()
        val maxX = cropX + cropW
        val maxY = cropY + cropH
        for (textBlock in visionText.textBlocks) {
            for (line in textBlock.lines) {
                val txt = line.text.trim()
                if (txt.isEmpty()) continue
                val box = line.boundingBox
                if (box != null) {
                    val left = (cropX + ((box.left - pad) / scale).roundToInt()).coerceIn(cropX, maxX)
                    val top = (cropY + ((box.top - pad) / scale).roundToInt()).coerceIn(cropY, maxY)
                    val right = (cropX + ((box.right - pad) / scale).roundToInt()).coerceIn(left, maxX)
                    val bottom = (cropY + ((box.bottom - pad) / scale).roundToInt()).coerceIn(top, maxY)
                    blocks += OcrBlock(
                        text = txt,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom
                    )
                } else {
                    blocks += OcrBlock(
                        text = txt,
                        left = cropX,
                        top = cropY,
                        right = maxX,
                        bottom = maxY
                    )
                }
            }
        }
        return blocks
    }

    private fun deduplicateBlocks(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 1) return blocks
        val seen = LinkedHashSet<String>(blocks.size)
        val out = ArrayList<OcrBlock>(blocks.size)
        for (b in blocks) {
            val key = "${b.text.lowercase()}|${b.left / 12}|${b.top / 12}"
            if (seen.add(key)) out += b
        }
        return out
    }
}
