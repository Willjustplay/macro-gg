package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.vision.PreparedTemplate
import com.example.vision.decodeFrame
import com.example.vision.savePng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fully offline local repository for macros, Lua scripts, target images, and settings.
 * Supports exporting/importing macros as portable .zip bundles (macro.json + referenced PNGs)
 * or plain .json files.
 */
class LocalStore(context: Context) {

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, "macrogg")
    private val macrosDir = File(rootDir, "macros").apply { mkdirs() }
    private val scriptsDir = File(rootDir, "scripts").apply { mkdirs() }
    private val imagesDir = File(rootDir, "images").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("macrogg_prefs", Context.MODE_PRIVATE)

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private val templateCache = ConcurrentHashMap<String, PreparedTemplate>()

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    private val _scripts = MutableStateFlow<List<LuaScript>>(emptyList())
    val scripts: StateFlow<List<LuaScript>> = _scripts.asStateFlow()

    private val _images = MutableStateFlow<List<ImageAsset>>(emptyList())
    val images: StateFlow<List<ImageAsset>> = _images.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        reloadAll()
        seedExamplesIfFirstRun()
    }

    fun reloadAll() {
        _macros.value = loadMacros()
        _scripts.value = loadScripts()
        _images.value = loadImages()
        _settings.value = loadSettings()
    }

    // ---- Settings ----

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply()
        _settings.value = settings
    }

    // ---- Macros ----

    private fun loadMacros(): List<Macro> {
        return macrosDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString<Macro>(file.readText()) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    fun saveMacro(macro: Macro): Macro {
        val updated = macro.copy(updatedAt = System.currentTimeMillis())
        val file = File(macrosDir, "${sanitizeFileName(updated.id)}.json")
        file.writeText(json.encodeToString(updated))
        _macros.value = loadMacros()
        return updated
    }

    fun getMacroById(id: String): Macro? =
        _macros.value.firstOrNull { it.id == id }
            ?: File(macrosDir, "${sanitizeFileName(id)}.json")
                .takeIf { it.exists() }
                ?.let { f -> runCatching { json.decodeFromString<Macro>(f.readText()) }.getOrNull() }

    fun deleteMacro(id: String) {
        File(macrosDir, "${sanitizeFileName(id)}.json").delete()
        _macros.value = loadMacros()
    }

    fun duplicateMacro(macro: Macro): Macro {
        val copy = macro.copy(
            id = newId(),
            name = "${macro.name} (Salinan)",
            actions = macro.actions.map { it.withNewIds() },
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return saveMacro(copy)
    }

    // ---- Lua Scripts ----

    private fun loadScripts(): List<LuaScript> {
        return scriptsDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString<LuaScript>(file.readText()) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    fun saveScript(script: LuaScript): LuaScript {
        val updated = script.copy(updatedAt = System.currentTimeMillis())
        val file = File(scriptsDir, "${sanitizeFileName(updated.id)}.json")
        file.writeText(json.encodeToString(updated))
        _scripts.value = loadScripts()
        return updated
    }

    fun deleteScript(id: String) {
        File(scriptsDir, "${sanitizeFileName(id)}.json").delete()
        _scripts.value = loadScripts()
    }

    // ---- Target Image Library ----

    private fun loadImages(): List<ImageAsset> {
        return imagesDir.listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@mapNotNull null
                ImageAsset(
                    name = file.name,
                    path = file.absolutePath,
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    sizeBytes = file.length(),
                    updatedAt = file.lastModified()
                )
            }
            .sortedByDescending { it.updatedAt }
    }

    fun imageFile(name: String): File {
        val normalized = normalizeImageName(name)
        return File(imagesDir, normalized)
    }

    fun saveImage(desiredName: String, bitmap: Bitmap): ImageAsset {
        val name = uniqueImageName(desiredName)
        val file = File(imagesDir, name)
        bitmap.savePng(file)
        templateCache.remove(name)
        _images.value = loadImages()
        return _images.value.first { it.name == name }
    }

    /** Overwrites an existing library image in-place (used when re-cropping an already-cropped image). */
    fun overwriteImage(existingName: String, bitmap: Bitmap): ImageAsset {
        val normalized = normalizeImageName(existingName)
        val file = File(imagesDir, normalized)
        bitmap.savePng(file)
        templateCache.remove(normalized)
        _images.value = loadImages()
        return _images.value.first { it.name == normalized }
    }

    fun saveImageBytes(desiredName: String, pngBytes: ByteArray): String {
        val file = imageFile(desiredName)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("File gambar tidak valid.")
        }
        file.parentFile?.mkdirs()
        if (bounds.outMimeType == "image/png") {
            file.writeBytes(pngBytes)
        } else {
            val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                ?: throw IllegalArgumentException("File gambar tidak valid.")
            try {
                bmp.savePng(file)
            } finally {
                bmp.recycle()
            }
        }
        templateCache.remove(file.name)
        _images.value = loadImages()
        return file.name
    }

    fun renameImage(oldName: String, newRawName: String): String? {
        val oldFile = imageFile(oldName)
        if (!oldFile.exists()) return null
        val newName = uniqueImageName(newRawName)
        val newFile = File(imagesDir, newName)
        if (!oldFile.renameTo(newFile)) return null
        templateCache.remove(oldFile.name)
        templateCache.remove(newFile.name)
        // Update any macro referencing the renamed image so export & detection stay intact
        val oldNormalized = oldFile.name
        for (m in _macros.value) {
            if (m.imageNames().any { normalizeImageName(it) == oldNormalized }) {
                val updated = m.copy(
                    actions = m.actions.mapAllActions { a ->
                        if (a is ImageAction && normalizeImageName(a.imageName) == oldNormalized) {
                            a.copy(imageName = newName)
                        } else a
                    }
                )
                saveMacro(updated)
            }
        }
        _images.value = loadImages()
        return newName
    }

    fun deleteImage(name: String) {
        val file = imageFile(name)
        file.delete()
        templateCache.remove(file.name)
        _images.value = loadImages()
    }

    fun exportImagePng(name: String, output: OutputStream): Boolean {
        val file = resolveExistingImageFile(name) ?: return false
        output.use { out ->
            out.write(file.readBytes())
        }
        return true
    }

    private fun resolveExistingImageFile(name: String): File? {
        val normalized = imageFile(name)
        if (normalized.exists()) return normalized
        val raw = File(imagesDir, name.substringAfterLast('/').substringAfterLast('\\'))
        return if (raw.exists()) raw else null
    }

    fun loadTemplate(name: String): PreparedTemplate? {
        val normalized = normalizeImageName(name)
        templateCache[normalized]?.let { return it }
        val file = File(imagesDir, normalized)
        if (!file.exists()) return null
        val frame = file.decodeFrame() ?: return null
        val prepared = PreparedTemplate(normalized, frame)
        templateCache[normalized] = prepared
        return prepared
    }

    fun uniqueImageName(baseRaw: String): String {
        val cleanBase = sanitizeFileName(baseRaw.removeSuffix(".png").removeSuffix(".PNG"))
            .ifBlank { "target_${System.currentTimeMillis() % 10000}" }
        var candidate = "$cleanBase.png"
        var counter = 2
        while (File(imagesDir, candidate).exists()) {
            candidate = "${cleanBase}_$counter.png"
            counter++
        }
        return candidate
    }

    // ---- Export / Import ----

    data class ExportSummary(
        val macrosExported: Int,
        val imagesExported: Int
    )

    /**
     * Exports a macro, all linked sub-macros, and all PNG target images they reference
     * into a portable .zip bundle (macro.json, linked_macros folder, and images folder).
     */
    fun exportMacroZip(macro: Macro, output: OutputStream): ExportSummary {
        val latest = getMacroById(macro.id) ?: macro
        val res = MacroBundleCodec.writeMacroZip(
            root = latest,
            lookupMacro = ::getMacroById,
            loadImage = { imgName ->
                resolveExistingImageFile(imgName)?.let { f -> f.name to f.readBytes() }
            },
            output = output
        )
        return ExportSummary(macrosExported = res.macrosExported, imagesExported = res.imagesExported)
    }

    /** Exports every macro, Lua script, and target image into a single backup .zip file. */
    fun exportAllBackupZip(output: OutputStream): ExportSummary {
        var imagesWritten = 0
        ZipOutputStream(output).use { zip ->
            for (macro in _macros.value) {
                zip.putNextEntry(ZipEntry("macros/${sanitizeFileName(macro.id)}.json"))
                zip.write(json.encodeToString(macro).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for (script in _scripts.value) {
                zip.putNextEntry(ZipEntry("scripts/${sanitizeFileName(script.id)}.json"))
                zip.write(json.encodeToString(script).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for (img in _images.value) {
                val f = File(img.path)
                if (f.exists()) {
                    zip.putNextEntry(ZipEntry("images/${f.name}"))
                    zip.write(f.readBytes())
                    zip.closeEntry()
                    imagesWritten++
                }
            }
        }
        return ExportSummary(macrosExported = _macros.value.size, imagesExported = imagesWritten)
    }

    data class ImportSummary(
        val macrosImported: Int,
        val scriptsImported: Int,
        val imagesImported: Int
    )

    /**
     * Imports either a single macro JSON, a single macro .zip bundle (with linked macros & PNGs),
     * or a full backup .zip. Preserves [RunMacroAction] links by remapping old macro IDs to new IDs.
     */
    fun importFromStream(input: InputStream): ImportSummary {
        val decoded = MacroBundleCodec.readBundle(input)
        var imagesCount = 0
        for ((fileName, bytes) in decoded.images) {
            runCatching {
                saveImageBytes(fileName, bytes)
                imagesCount++
            }
        }
        for (script in decoded.scripts) {
            saveScript(script)
        }
        for (macro in decoded.macros) {
            saveMacro(macro)
        }
        return ImportSummary(
            macrosImported = decoded.macros.size,
            scriptsImported = decoded.scripts.size,
            imagesImported = imagesCount
        )
    }

    private fun seedExamplesIfFirstRun() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        if (_macros.value.isEmpty()) {
            saveMacro(
                Macro(
                    name = "Contoh Auto Clicker",
                    loopCount = 5,
                    loopDelayMs = 600,
                    jitterPx = 4,
                    actions = listOf(
                        ClickAction(x = 540, y = 1200, durationMs = 50, delayAfterMs = 400),
                        WaitAction(durationMs = 500, randomMs = 200),
                        SwipeAction(x1 = 540, y1 = 1500, x2 = 540, y2 = 900, durationMs = 300, delayAfterMs = 400)
                    )
                )
            )
        }
        if (_scripts.value.isEmpty()) {
            saveScript(
                LuaScript(
                    name = "Contoh Deteksi Gambar",
                    code = DEFAULT_LUA_SAMPLE
                )
            )
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    companion object {
        private const val KEY_SETTINGS = "app_settings_json"
        private const val KEY_SEEDED = "seeded_v2"

        @Volatile
        private var instance: LocalStore? = null

        fun get(context: Context): LocalStore =
            instance ?: synchronized(this) {
                instance ?: LocalStore(context.applicationContext).also { instance = it }
            }

        fun normalizeImageName(raw: String): String {
            val trimmed = raw.trim().substringAfterLast('/').substringAfterLast('\\')
            val base = sanitizeFileName(trimmed.removeSuffix(".png").removeSuffix(".PNG"))
            return if (base.isBlank()) "target.png" else "$base.png"
        }

        fun sanitizeFileName(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(64)

        val DEFAULT_LUA_SAMPLE = """
-- Contoh Macro Lua dengan Image Detection
local w, h = getScreenSize()
log("Layar aktif: " .. w .. "x" .. h)

for i = 1, 5 do
    if not isRunning() then break end

    -- Cari gambar dari pustaka Gambar (crop dulu lewat tombol kamera di overlay)
    local ok, x, y, score = waitImage("tombol_play.png", 0.85, 3000)
    if ok then
        log("Ditemukan di (" .. x .. ", " .. y .. ") skor=" .. string.format("%.0f%%", score * 100))
        click(x, y)
        sleep(1000)
    else
        log("Gambar belum muncul (loop #" .. i .. ")")
        sleep(500)
    end
end
log("Selesai!")
        """.trimIndent()
    }
}
