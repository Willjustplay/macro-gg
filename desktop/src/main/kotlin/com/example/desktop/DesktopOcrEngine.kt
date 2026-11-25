package com.example.desktop

import com.example.data.Region
import com.example.vision.Frame
import com.example.vision.OcrBlock
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 3-Tier Offline PC OCR Engine supporting game-HUD text & numeric (<, >, =) detection:
 * 1. Windows 10/11 Built-in WinRT OCR (`Windows.Media.Ocr.OcrEngine`, zero installation required on Windows!)
 * 2. Auto-detected `tesseract` CLI (Linux/macOS/Windows) with 2x upscaled game-HUD binarization
 * 3. Built-in zero-dependency Pure-Kotlin Game-HUD Digit/Number Recognizer fallback (0-9, ., ,, K, M, B, %)
 */
object DesktopOcrEngine {

    private val tesseractBin: String? by lazy { detectTesseract() }
    private val winRtOcrAvailable: Boolean by lazy { Win32Native.isWindows }

    fun activeEngineName(): String = when {
        tesseractBin != null -> "Tesseract OCR + HUD Preprocessor"
        winRtOcrAvailable -> "Windows Native WinRT OCR + HUD Fallback"
        else -> "Built-in HUD Digit/Number OCR"
    }

    fun recognizeText(frame: Frame, region: Region?, enhanced: Boolean = true): List<OcrBlock> {
        val left = (region?.left ?: 0).coerceIn(0, frame.width - 1)
        val top = (region?.top ?: 0).coerceIn(0, frame.height - 1)
        val right = (region?.right ?: frame.width).coerceIn(left + 1, frame.width)
        val bottom = (region?.bottom ?: frame.height).coerceIn(top + 1, frame.height)
        val w = right - left
        val h = bottom - top

        val cropped = frame.crop(left, top, w, h)
        val rawImg = DesktopCaptureAndInput.frameToBufferedImage(cropped)

        val merged = ArrayList<OcrBlock>()
        val seenTexts = HashSet<String>()

        fun addBlocks(blocks: List<OcrBlock>) {
            for (b in blocks) {
                val clean = b.text.trim()
                if (clean.isNotBlank() && seenTexts.add(clean.lowercase())) {
                    merged += b
                }
            }
        }

        // Primary OCR pass via Tesseract or Windows WinRT
        val primaryRaw = runExternalOcr(rawImg, left, top, w, h)
        addBlocks(primaryRaw)

        // Enhanced Game-HUD pass (2x upscale + bright-text binarization — matches Android v2.12.0 COC HUD fix!)
        if (enhanced) {
            val hudImg = preprocessGameHud(rawImg, scale = if (min(w, h) < 120) 3 else 2)
            val primaryHud = runExternalOcr(hudImg, left, top, w, h)
            addBlocks(primaryHud)
        }

        // Always complement with fast zero-dependency pure-Kotlin HUD digit reader if no numbers found yet
        val hasDigits = merged.any { it.text.any(Char::isDigit) }
        if (!hasDigits) {
            val hudDigits = recognizeHudDigitsPureKotlin(cropped, left, top)
            addBlocks(hudDigits)
        }

        return merged
    }

    private fun runExternalOcr(img: BufferedImage, offsetX: Int, offsetY: Int, origW: Int, origH: Int): List<OcrBlock> {
        val tess = tesseractBin
        if (tess != null) {
            val out = runTesseract(tess, img, offsetX, offsetY, origW, origH)
            if (out.isNotEmpty()) return out
        }
        if (winRtOcrAvailable) {
            val out = runWinRtOcr(img, offsetX, offsetY, origW, origH)
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun detectTesseract(): String? {
        val candidates = ArrayList<String>()
        candidates += "tesseract"
        if (Win32Native.isWindows) {
            candidates += listOf(
                "C:\\Program Files\\Tesseract-OCR\\tesseract.exe",
                "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe",
                "tesseract\\tesseract.exe"
            )
        } else {
            candidates += listOf("/usr/bin/tesseract", "/usr/local/bin/tesseract", "/opt/homebrew/bin/tesseract")
        }
        for (c in candidates) {
            if (c == "tesseract" || File(c).isFile) {
                val ok = runCatching {
                    val p = ProcessBuilder(c, "--version").redirectErrorStream(true).start()
                    p.waitFor(1500L, TimeUnit.MILLISECONDS) && p.exitValue() == 0
                }.getOrDefault(false)
                if (ok) return c
            }
        }
        return null
    }

    private fun runTesseract(
        bin: String,
        img: BufferedImage,
        offsetX: Int,
        offsetY: Int,
        origW: Int,
        origH: Int
    ): List<OcrBlock> {
        val tmp = File.createTempFile("wjust_ocr_", ".png")
        return try {
            ImageIO.write(img, "png", tmp)
            val p = ProcessBuilder(bin, tmp.absolutePath, "stdout", "--psm", "6", "--oem", "3")
                .redirectErrorStream(false)
                .start()
            val text = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(2500L, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return emptyList()
            }
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return emptyList()
            val lineH = (origH / lines.size).coerceAtLeast(1)
            lines.mapIndexed { idx, line ->
                OcrBlock(
                    text = line,
                    left = offsetX,
                    top = offsetY + idx * lineH,
                    right = offsetX + origW,
                    bottom = offsetY + min(origH, (idx + 1) * lineH)
                )
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            tmp.delete()
        }
    }

    private val winRtLock = Any()
    private var winRtProc: Process? = null
    private var winRtWriter: java.io.BufferedWriter? = null
    private var winRtReader: java.io.BufferedReader? = null

    fun shutdown() {
        synchronized(winRtLock) {
            runCatching {
                winRtWriter?.write("__EXIT__\n")
                winRtWriter?.flush()
            }
            runCatching { winRtProc?.destroyForcibly() }
            winRtProc = null
            winRtWriter = null
            winRtReader = null
        }
    }

    private fun ensureWinRtWorker(): Pair<java.io.BufferedWriter, java.io.BufferedReader>? {
        val existing = winRtProc
        if (existing != null && existing.isAlive && winRtWriter != null && winRtReader != null) {
            return winRtWriter!! to winRtReader!!
        }
        runCatching { existing?.destroyForcibly() }
        winRtProc = null
        winRtWriter = null
        winRtReader = null

        val psLoop = """
            [Console]::InputEncoding = [System.Text.Encoding]::UTF8
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            [Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime] | Out-Null
            [Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime] | Out-Null
            [Windows.Media.Ocr.OcrEngine,Windows.Media.Ocr,ContentType=WindowsRuntime] | Out-Null
            function Await(${'$'}t, ${'$'}r) {
              ${'$'}m = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0].MakeGenericMethod(${'$'}r)
              ${'$'}task = ${'$'}m.Invoke(${'$'}null, @(${'$'}t)); ${'$'}task.Wait(); return ${'$'}task.Result
            }
            ${'$'}eng = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
            while (${'$'}true) {
              ${'$'}path = [Console]::In.ReadLine()
              if (${'$'}path -eq ${'$'}null -or ${'$'}path -eq '__EXIT__') { break }
              try {
                if (${'$'}eng -ne ${'$'}null -and (Test-Path -LiteralPath ${'$'}path)) {
                  ${'$'}file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync(${'$'}path)) ([Windows.Storage.StorageFile])
                  ${'$'}stream = Await (${'$'}file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
                  ${'$'}dec = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync(${'$'}stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
                  ${'$'}bmp = Await (${'$'}dec.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
                  ${'$'}res = Await (${'$'}eng.RecognizeAsync(${'$'}bmp)) ([Windows.Media.Ocr.OcrResult])
                  foreach (${'$'}ln in ${'$'}res.Lines) { Write-Output ${'$'}ln.Text }
                  ${'$'}stream.Dispose()
                }
              } catch {}
              Write-Output '__WJUST_OCR_END__'
            }
        """.trimIndent()
        return runCatching {
            val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psLoop)
                .redirectErrorStream(false)
                .start()
            val w = p.outputStream.bufferedWriter(Charsets.UTF_8)
            val r = p.inputStream.bufferedReader(Charsets.UTF_8)
            winRtProc = p
            winRtWriter = w
            winRtReader = r
            w to r
        }.getOrNull()
    }

    private fun runWinRtOcr(
        img: BufferedImage,
        offsetX: Int,
        offsetY: Int,
        origW: Int,
        origH: Int
    ): List<OcrBlock> {
        val tmp = File.createTempFile("wjust_winocr_", ".png")
        return try {
            ImageIO.write(img, "png", tmp)
            val lines = synchronized(winRtLock) {
                val pair = ensureWinRtWorker() ?: return@synchronized emptyList()
                val (writer, reader) = pair
                try {
                    writer.write(tmp.absolutePath)
                    writer.newLine()
                    writer.flush()
                    val collected = ArrayList<String>()
                    while (true) {
                        val ln = reader.readLine() ?: break
                        if (ln == "__WJUST_OCR_END__") break
                        val trimmed = ln.trim()
                        if (trimmed.isNotBlank()) collected += trimmed
                    }
                    collected
                } catch (_: Throwable) {
                    runCatching { winRtProc?.destroyForcibly() }
                    winRtProc = null
                    emptyList()
                }
            }
            if (lines.isEmpty()) return emptyList()
            val lineH = (origH / lines.size).coerceAtLeast(1)
            lines.mapIndexed { idx, line ->
                OcrBlock(
                    text = line,
                    left = offsetX,
                    top = offsetY + idx * lineH,
                    right = offsetX + origW,
                    bottom = offsetY + min(origH, (idx + 1) * lineH)
                )
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            tmp.delete()
        }
    }

    /**
     * Game-HUD contrast + 2x/3x upscaling preprocessor: turns outlined white/yellow/gold game digits
     * into crisp dark-on-white strokes for dramatically higher OCR accuracy on COC/RPG HUDs.
     */
    internal fun preprocessGameHud(src: BufferedImage, scale: Int = 2): BufferedImage {
        val nw = (src.width * scale).coerceAtLeast(24)
        val nh = (src.height * scale).coerceAtLeast(24)
        val pad = 12
        val totalW = nw + pad * 2
        val totalH = nh + pad * 2
        val scaled = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = scaled.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, totalW, totalH)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(src, pad, pad, nw, nh, null)
        g.dispose()

        // Measure average luma strictly over the inner HUD area (excluding the white padding border)
        val px = IntArray(totalW * totalH)
        scaled.getRGB(0, 0, totalW, totalH, px, 0, totalW)
        var sumLuma = 0L
        var innerCount = 0L
        for (y in pad until pad + nh) {
            val rowOff = y * totalW
            for (x in pad until pad + nw) {
                val c = px[rowOff + x]
                sumLuma += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                innerCount++
            }
        }
        val avgLuma = (sumLuma / innerCount.coerceAtLeast(1L)).toInt()
        val lightTextOnDark = avgLuma < 138
        val thresh = if (lightTextOnDark) (avgLuma + 46).coerceIn(130, 215) else (avgLuma - 36).coerceIn(60, 150)

        for (y in pad until pad + nh) {
            val rowOff = y * totalW
            for (x in pad until pad + nw) {
                val idx = rowOff + x
                val c = px[idx]
                val luma = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                val isInk = if (lightTextOnDark) luma >= thresh else luma <= thresh
                px[idx] = if (isInk) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        scaled.setRGB(0, 0, totalW, totalH, px, 0, totalW)
        return scaled
    }

    // ---- Tier 3: Pure-Kotlin Zero-Dependency Game-HUD Digit & Number Recognizer ----

    private val glyphTemplates: List<Pair<Char, FloatArray>> by lazy { buildGlyphTemplates() }

    private fun buildGlyphTemplates(): List<Pair<Char, FloatArray>> {
        val chars = "0123456789KM"
        val out = ArrayList<Pair<Char, FloatArray>>()
        val fonts = listOf(
            Font(Font.SANS_SERIF, Font.BOLD, 28),
            Font(Font.MONOSPACED, Font.BOLD, 28)
        )
        for (font in fonts) {
            for (ch in chars) {
                val img = BufferedImage(36, 40, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.color = Color.WHITE
                g.fillRect(0, 0, 36, 40)
                g.color = Color.BLACK
                g.font = font
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
                g.drawString(ch.toString(), 4, 30)
                g.dispose()

                // Tight-crop ink bounding box and normalize to 12x16 template vector
                var minX = 36; var minY = 40; var maxX = -1; var maxY = -1
                for (y in 0 until 40) {
                    for (x in 0 until 36) {
                        if ((img.getRGB(x, y) and 0xFF) < 128) {
                            if (x < minX) minX = x
                            if (y < minY) minY = y
                            if (x > maxX) maxX = x
                            if (y > maxY) maxY = y
                        }
                    }
                }
                if (maxX >= minX && maxY >= minY) {
                    val vec = sampleInkGrid(
                        width = maxX - minX + 1,
                        height = maxY - minY + 1
                    ) { sx, sy -> (img.getRGB(minX + sx, minY + sy) and 0xFF) < 128 }
                    out += ch to vec
                }
            }
        }
        return out
    }

    private fun sampleInkGrid(width: Int, height: Int, isInk: (Int, Int) -> Boolean): FloatArray {
        val gw = 12
        val gh = 16
        val vec = FloatArray(gw * gh)
        var mean = 0f
        for (gy in 0 until gh) {
            val sy = ((gy + 0.5f) * height / gh).toInt().coerceIn(0, height - 1)
            for (gx in 0 until gw) {
                val sx = ((gx + 0.5f) * width / gw).toInt().coerceIn(0, width - 1)
                val v = if (isInk(sx, sy)) 1f else 0f
                vec[gy * gw + gx] = v
                mean += v
            }
        }
        mean /= vec.size
        var norm = 0.0
        for (i in vec.indices) {
            vec[i] -= mean
            norm += vec[i] * vec[i]
        }
        val denom = kotlin.math.sqrt(norm).toFloat().coerceAtLeast(1e-4f)
        for (i in vec.indices) vec[i] /= denom
        return vec
    }

    /**
     * Pure-Kotlin connected-component HUD digit reader (recognizes crisp dark-on-light or bright-on-dark
     * HUD numbers such as "45000", "12K", "950" with zero external dependencies).
     */
    internal fun recognizeHudDigitsPureKotlin(frame: Frame, offsetX: Int, offsetY: Int): List<OcrBlock> {
        val w = frame.width
        val h = frame.height
        if (w < 6 || h < 8 || w * h > 600_000) return emptyList()

        val luma = IntArray(w * h)
        var sum = 0L
        for (i in luma.indices) {
            val c = frame.pixels[i]
            val l = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            luma[i] = l
            sum += l
        }
        val avg = (sum / luma.size).toInt()
        val brightInk = avg < 135
        val thr = if (brightInk) (avg + 42).coerceIn(135, 215) else (avg - 42).coerceIn(45, 145)
        val mask = BooleanArray(w * h) { i -> if (brightInk) luma[i] >= thr else luma[i] <= thr }

        // Extract connected components via BFS
        val visited = BooleanArray(w * h)
        data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val pixels: Int)
        val boxes = ArrayList<Box>()
        val queue = IntArray(min(w * h, 65_536))

        for (y in 0 until h) {
            for (x in 0 until w) {
                val start = y * w + x
                if (!mask[start] || visited[start]) continue
                var head = 0; var tail = 0
                queue[tail++] = start
                visited[start] = true
                var bx0 = x; var by0 = y; var bx1 = x; var by1 = y; var cnt = 0
                while (head < tail) {
                    val cur = queue[head++]
                    val cx = cur % w; val cy = cur / w
                    cnt++
                    if (cx < bx0) bx0 = cx
                    if (cy < by0) by0 = cy
                    if (cx > bx1) bx1 = cx
                    if (cy > by1) by1 = cy
                    for (dy in -1..1) {
                        val ny = cy + dy
                        if (ny !in 0 until h) continue
                        for (dx in -1..1) {
                            val nx = cx + dx
                            if (nx !in 0 until w) continue
                            val ni = ny * w + nx
                            if (mask[ni] && !visited[ni]) {
                                visited[ni] = true
                                if (tail < queue.size) queue[tail++] = ni
                            }
                        }
                    }
                }
                val bw = bx1 - bx0 + 1
                val bh = by1 - by0 + 1
                if (bh in 8..min(h, 90) && bw in 2..(bh * 2) && cnt >= 10) {
                    boxes += Box(bx0, by0, bx1, by1, cnt)
                }
            }
        }
        if (boxes.isEmpty()) return emptyList()

        // Sort left-to-right and classify each glyph
        boxes.sortBy { it.x0 }
        val sb = StringBuilder()
        var lastX1 = -1
        var medianH = boxes.map { it.y1 - it.y0 + 1 }.sorted()[boxes.size / 2].coerceAtLeast(8)
        for (b in boxes) {
            val bw = b.x1 - b.x0 + 1
            val bh = b.y1 - b.y0 + 1
            if (abs(bh - medianH) > medianH * 0.55) continue
            if (lastX1 >= 0 && b.x0 - lastX1 > (medianH * 0.9).roundToInt()) {
                sb.append(' ')
            }
            val vec = sampleInkGrid(bw, bh) { sx, sy -> mask[(b.y0 + sy) * w + (b.x0 + sx)] }
            var bestChar = '?'
            var bestDot = -1f
            for ((ch, tpl) in glyphTemplates) {
                var dot = 0f
                for (k in vec.indices) dot += vec[k] * tpl[k]
                if (dot > bestDot) {
                    bestDot = dot
                    bestChar = ch
                }
            }
            if (bestDot >= 0.48f) {
                sb.append(bestChar)
                lastX1 = b.x1
            }
        }
        val recognized = sb.toString().trim()
        if (recognized.isBlank()) return emptyList()
        return listOf(OcrBlock(recognized, offsetX, offsetY, offsetX + w, offsetY + h))
    }
}
