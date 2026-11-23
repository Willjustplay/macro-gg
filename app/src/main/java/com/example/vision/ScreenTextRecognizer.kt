package com.example.vision

import android.graphics.Bitmap
import com.example.data.Region
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 100% offline on-device OCR wrapper using ML Kit bundled Latin/digits Text Recognition.
 * Crops the requested [Region] (upscaling small regions 2x for crisp game HUD digit accuracy)
 * and maps recognized text line/element bounds back into real screen pixel coordinates.
 */
object ScreenTextRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(fullScreenshot: Bitmap, region: Region?): List<OcrBlock> =
        withContext(Dispatchers.Default) {
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

            var cropped: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                val source = if (cropX == 0 && cropY == 0 && cropW == fullScreenshot.width && cropH == fullScreenshot.height) {
                    fullScreenshot
                } else {
                    Bitmap.createBitmap(fullScreenshot, cropX, cropY, cropW, cropH).also { cropped = it }
                }

                // Upscale small HUD crops (like HP bars / gold counters) so ML Kit reads tiny digits reliably
                val scale = if (source.width < 420 && source.height < 260) 2f else 1f
                val inputBmp = if (scale > 1f) {
                    Bitmap.createScaledBitmap(
                        source,
                        (source.width * scale).toInt().coerceAtLeast(1),
                        (source.height * scale).toInt().coerceAtLeast(1),
                        true
                    ).also { scaled = it }
                } else {
                    source
                }

                val image = InputImage.fromBitmap(inputBmp, 0)
                val visionText = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { result ->
                            if (cont.isActive) cont.resume(result)
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                } ?: return@withContext emptyList()

                val blocks = ArrayList<OcrBlock>()
                for (textBlock in visionText.textBlocks) {
                    for (line in textBlock.lines) {
                        val txt = line.text.trim()
                        if (txt.isEmpty()) continue
                        val box = line.boundingBox
                        if (box != null) {
                            blocks += OcrBlock(
                                text = txt,
                                left = cropX + (box.left / scale).toInt(),
                                top = cropY + (box.top / scale).toInt(),
                                right = cropX + (box.right / scale).toInt(),
                                bottom = cropY + (box.bottom / scale).toInt()
                            )
                        } else {
                            blocks += OcrBlock(
                                text = txt,
                                left = cropX,
                                top = cropY,
                                right = cropX + cropW,
                                bottom = cropY + cropH
                            )
                        }
                    }
                }
                blocks
            } catch (_: Throwable) {
                emptyList()
            } finally {
                scaled?.recycle()
                cropped?.recycle()
            }
        }
}
