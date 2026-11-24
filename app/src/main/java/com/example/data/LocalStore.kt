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
    private val macroIconsDir = File(rootDir, "macro_icons").apply { mkdirs() }
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

    private val _imageFolders = MutableStateFlow<List<String>>(emptyList())
    val imageFolders: StateFlow<List<String>> = _imageFolders.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        effectiveNowMs()
        reloadAll()
        seedExamplesIfFirstRun()
    }

    /**
     * Monotonic wall-clock watermark to prevent trivial device-clock rollback from bypassing
     * macro expiration deadlines offline.
     */
    fun effectiveNowMs(): Long {
        val sys = System.currentTimeMillis()
        val maxSeen = prefs.getLong(KEY_MAX_SEEN_EPOCH_MS, 0L)
        if (sys > maxSeen) {
            prefs.edit().putLong(KEY_MAX_SEEN_EPOCH_MS, sys).apply()
            return sys
        }
        return maxSeen
    }

    fun reloadAll() {
        _macros.value = loadMacros()
        _scripts.value = loadScripts()
        _imageFolders.value = loadImageFolders()
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

    // ---- Macros & Custom Macro Logos ----

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
        val existing = getMacroById(id)
        File(macrosDir, "${sanitizeFileName(id)}.json").delete()
        _macros.value = loadMacros()
        if (existing != null && existing.logoImage.isNotBlank()) {
            cleanupUnusedMacroIcon(existing.logoImage)
        }
    }

    fun duplicateMacro(macro: Macro): Macro {
        val newMacroId = newId()
        val copiedLogo = if (macro.logoImage.isNotBlank()) {
            val src = macroIconFile(macro.logoImage)
            if (src != null && src.exists()) {
                val destName = "logo_${sanitizeFileName(newMacroId)}.png"
                runCatching {
                    src.copyTo(File(macroIconsDir, destName), overwrite = true)
                    destName
                }.getOrDefault("")
            } else ""
        } else ""
        val copy = macro.copy(
            id = newMacroId,
            name = "${macro.name} (Salinan)",
            logoImage = copiedLogo,
            actions = macro.actions.map { it.withNewIds() },
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return saveMacro(copy)
    }

    fun macroIconFile(iconName: String): File? {
        val clean = sanitizeIconFileName(iconName)
        if (clean.isBlank()) return null
        return File(macroIconsDir, clean)
    }

    fun macroIconPath(iconName: String): String? =
        macroIconFile(iconName)?.takeIf { it.exists() }?.absolutePath

    /** Scales & center-crops [bitmap] to a crisp 256x256 PNG stored in macro_icons/. */
    fun saveMacroLogo(macroId: String, bitmap: Bitmap): String {
        val size = minOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val xOff = ((bitmap.width - size) / 2).coerceAtLeast(0)
        val yOff = ((bitmap.height - size) / 2).coerceAtLeast(0)
        val square = Bitmap.createBitmap(bitmap, xOff, yOff, size, size)
        val targetPx = minOf(size, 256)
        val finalBmp = if (size != targetPx) {
            Bitmap.createScaledBitmap(square, targetPx, targetPx, true).also {
                if (it !== square) square.recycle()
            }
        } else {
            square
        }

        val prevLogo = getMacroById(macroId)?.logoImage.orEmpty()
        val fileName = "logo_${sanitizeFileName(macroId)}_${System.currentTimeMillis() % 100000}.png"
        val outFile = File(macroIconsDir, fileName)
        try {
            finalBmp.savePng(outFile)
        } finally {
            if (finalBmp !== bitmap) finalBmp.recycle()
        }
        if (prevLogo.isNotBlank() && prevLogo != fileName) {
            cleanupUnusedMacroIcon(prevLogo, excludingMacroId = macroId)
        }
        return fileName
    }

    fun saveMacroLogoBytes(rawFileName: String, bytes: ByteArray): String? {
        val clean = sanitizeIconFileName(rawFileName)
        if (clean.isBlank()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val file = File(macroIconsDir, clean)
        if (bounds.outMimeType == "image/png") {
            file.writeBytes(bytes)
        } else {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            try {
                bmp.savePng(file)
            } finally {
                bmp.recycle()
            }
        }
        return clean
    }

    fun copyLibraryImageAsMacroLogo(macroId: String, libraryImageName: String): String? {
        val file = resolveExistingImageFile(libraryImageName) ?: return null
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            saveMacroLogo(macroId, bmp)
        } finally {
            bmp.recycle()
        }
    }

    fun cleanupUnusedMacroIcon(iconName: String, excludingMacroId: String = "") {
        val clean = sanitizeIconFileName(iconName)
        if (clean.isBlank()) return
        val stillUsed = _macros.value.any { it.id != excludingMacroId && sanitizeIconFileName(it.logoImage) == clean }
        if (!stillUsed) {
            File(macroIconsDir, clean).delete()
        }
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

    // ---- Target Image Library & Folders ----

    private fun loadImageFolders(): List<String> {
        return imagesDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .map { it.name }
            .sortedBy { it.lowercase() }
    }

    private fun refreshImagesAndFolders() {
        _imageFolders.value = loadImageFolders()
        _images.value = loadImages()
    }

    private fun scanPngsIn(dir: File, folderName: String): List<ImageAsset> {
        return dir.listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@mapNotNull null
                val relName = if (folderName.isBlank()) file.name else "$folderName/${file.name}"
                ImageAsset(
                    name = relName,
                    path = file.absolutePath,
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    sizeBytes = file.length(),
                    updatedAt = file.lastModified(),
                    folder = folderName
                )
            }
    }

    private fun loadImages(): List<ImageAsset> {
        val all = ArrayList<ImageAsset>()
        all += scanPngsIn(imagesDir, "")
        imagesDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .forEach { sub ->
                all += scanPngsIn(sub, sub.name)
            }
        return all.sortedByDescending { it.updatedAt }
    }

    fun createImageFolder(rawName: String): String? {
        val clean = sanitizeFolderName(rawName)
        if (clean.isBlank()) return null
        val dir = File(imagesDir, clean)
        if (!dir.exists() && !dir.mkdirs()) return null
        _imageFolders.value = loadImageFolders()
        return clean
    }

    /**
     * Deletes a folder. Any images inside are safely moved back to root ("") and macro references updated.
     */
    fun deleteImageFolder(folderName: String): Boolean {
        val clean = sanitizeFolderName(folderName)
        if (clean.isBlank()) return false
        val dir = File(imagesDir, clean)
        if (!dir.exists() || !dir.isDirectory) return false
        val inside = scanPngsIn(dir, clean).map { it.name }
        if (inside.isNotEmpty()) {
            moveImages(inside, "")
        }
        val deleted = dir.delete()
        refreshImagesAndFolders()
        return deleted
    }

    fun imageFile(name: String): File {
        val normalized = normalizeImageName(name)
        return File(imagesDir, normalized)
    }

    private fun relativeImageName(file: File): String {
        val parent = file.parentFile
        return if (parent != null && parent != imagesDir && parent.parentFile == imagesDir) {
            "${parent.name}/${file.name}"
        } else {
            file.name
        }
    }

    fun saveImage(desiredName: String, bitmap: Bitmap, folder: String = ""): ImageAsset {
        val name = uniqueImageName(desiredName, folder)
        val file = File(imagesDir, name)
        file.parentFile?.mkdirs()
        bitmap.savePng(file)
        templateCache.remove(name)
        refreshImagesAndFolders()
        return _images.value.first { it.name == name }
    }

    /** Overwrites an existing library image in-place (used when re-cropping an already-cropped image). */
    fun overwriteImage(existingName: String, bitmap: Bitmap): ImageAsset {
        val normalized = normalizeImageName(existingName)
        val file = File(imagesDir, normalized)
        file.parentFile?.mkdirs()
        bitmap.savePng(file)
        templateCache.remove(normalized)
        refreshImagesAndFolders()
        return _images.value.first { it.name == normalized }
    }

    fun saveImageBytes(desiredName: String, pngBytes: ByteArray): String {
        val normalized = normalizeImageName(desiredName)
        val file = File(imagesDir, normalized)
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
        templateCache.remove(normalized)
        refreshImagesAndFolders()
        return normalized
    }

    private fun rewriteMacroImageReference(oldNormalized: String, newNormalized: String) {
        if (oldNormalized == newNormalized) return
        for (m in _macros.value) {
            if (m.imageNames().any { normalizeImageName(it) == oldNormalized }) {
                val updated = m.copy(
                    actions = m.actions.mapAllActions { a ->
                        if (a is ImageAction && normalizeImageName(a.imageName) == oldNormalized) {
                            a.copy(imageName = newNormalized)
                        } else a
                    }
                )
                saveMacro(updated)
            }
        }
    }

    fun renameImage(oldName: String, newRawName: String): String? {
        val oldNormalized = normalizeImageName(oldName)
        val oldFile = File(imagesDir, oldNormalized)
        if (!oldFile.exists()) return null
        val currentFolder = oldNormalized.substringBefore('/', missingDelimiterValue = "")
        val newName = uniqueImageName(newRawName.substringAfterLast('/'), currentFolder)
        val newFile = File(imagesDir, newName)
        newFile.parentFile?.mkdirs()
        if (!oldFile.renameTo(newFile)) return null
        templateCache.remove(oldNormalized)
        templateCache.remove(newName)
        rewriteMacroImageReference(oldNormalized, newName)
        refreshImagesAndFolders()
        return newName
    }

    /** Moves one or more images into [targetFolder] (empty string = Utama/root), updating macro references. */
    fun moveImages(names: Collection<String>, targetFolder: String): Int {
        val cleanFolder = sanitizeFolderName(targetFolder)
        if (cleanFolder.isNotBlank()) {
            File(imagesDir, cleanFolder).mkdirs()
        }
        var moved = 0
        for (raw in names) {
            val oldNorm = normalizeImageName(raw)
            val oldFile = File(imagesDir, oldNorm)
            if (!oldFile.exists()) continue
            val currentFolder = oldNorm.substringBefore('/', missingDelimiterValue = "")
            if (currentFolder == cleanFolder) continue
            val baseName = oldFile.name.removeSuffix(".png").removeSuffix(".PNG")
            val newNorm = uniqueImageName(baseName, cleanFolder)
            val newFile = File(imagesDir, newNorm)
            newFile.parentFile?.mkdirs()
            if (oldFile.renameTo(newFile)) {
                templateCache.remove(oldNorm)
                templateCache.remove(newNorm)
                rewriteMacroImageReference(oldNorm, newNorm)
                moved++
            }
        }
        if (moved > 0) refreshImagesAndFolders()
        return moved
    }

    fun deleteImage(name: String) {
        val normalized = normalizeImageName(name)
        File(imagesDir, normalized).delete()
        templateCache.remove(normalized)
        refreshImagesAndFolders()
    }

    fun deleteImages(names: Collection<String>): Int {
        var deleted = 0
        for (name in names) {
            val normalized = normalizeImageName(name)
            if (File(imagesDir, normalized).delete()) {
                templateCache.remove(normalized)
                deleted++
            }
        }
        if (deleted > 0) refreshImagesAndFolders()
        return deleted
    }

    fun exportImagePng(name: String, output: OutputStream): Boolean {
        val file = resolveExistingImageFile(name) ?: return false
        output.use { out ->
            out.write(file.readBytes())
        }
        return true
    }

    /** Exports multiple selected images into a single portable .zip archive. */
    fun exportImagesZip(names: Collection<String>, output: OutputStream): Int {
        var count = 0
        val written = HashSet<String>()
        ZipOutputStream(output).use { zip ->
            for (name in names) {
                val file = resolveExistingImageFile(name) ?: continue
                val rel = relativeImageName(file)
                if (written.add(rel)) {
                    zip.putNextEntry(ZipEntry("images/$rel"))
                    zip.write(file.readBytes())
                    zip.closeEntry()
                    count++
                }
            }
        }
        return count
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
        val file = resolveExistingImageFile(normalized) ?: return null
        val frame = file.decodeFrame() ?: return null
        val prepared = PreparedTemplate(normalized, frame)
        templateCache[normalized] = prepared
        return prepared
    }

    fun uniqueImageName(baseRaw: String, folder: String = ""): String {
        val cleanFolder = sanitizeFolderName(folder)
        val cleanBase = sanitizeFileName(baseRaw.substringAfterLast('/').removeSuffix(".png").removeSuffix(".PNG"))
            .ifBlank { "target_${System.currentTimeMillis() % 10000}" }
        val prefix = if (cleanFolder.isBlank()) "" else "$cleanFolder/"
        var candidate = "$prefix$cleanBase.png"
        var counter = 2
        while (File(imagesDir, candidate).exists()) {
            candidate = "$prefix${cleanBase}_$counter.png"
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
                resolveExistingImageFile(imgName)?.let { f -> relativeImageName(f) to f.readBytes() }
            },
            output = output,
            loadMacroIcon = { iconName ->
                macroIconFile(iconName)?.takeIf { it.exists() }?.let { f -> f.name to f.readBytes() }
            }
        )
        return ExportSummary(macrosExported = res.macrosExported, imagesExported = res.imagesExported)
    }

    /** Exports every macro, Lua script, target image, and custom macro logo into a single backup .zip file. */
    fun exportAllBackupZip(output: OutputStream): ExportSummary {
        var imagesWritten = 0
        val writtenIcons = HashSet<String>()
        ZipOutputStream(output).use { zip ->
            for (macro in _macros.value) {
                zip.putNextEntry(ZipEntry("macros/${sanitizeFileName(macro.id)}.json"))
                zip.write(json.encodeToString(macro).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (macro.logoImage.isNotBlank()) {
                    val f = macroIconFile(macro.logoImage)
                    if (f != null && f.exists() && writtenIcons.add(f.name)) {
                        zip.putNextEntry(ZipEntry("macro_icons/${f.name}"))
                        zip.write(f.readBytes())
                        zip.closeEntry()
                    }
                }
            }
            for (script in _scripts.value) {
                zip.putNextEntry(ZipEntry("scripts/${sanitizeFileName(script.id)}.json"))
                zip.write(json.encodeToString(script).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for (img in _images.value) {
                val f = File(img.path)
                if (f.exists()) {
                    zip.putNextEntry(ZipEntry("images/${img.name}"))
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
        val imagesImported: Int,
        val macrosOverwritten: Int = 0,
        val deadlinesExtended: Int = 0
    )

    private fun findMatchingMacroForOverwrite(incoming: Macro, candidates: List<Macro>): Macro? {
        val incOrigin = incoming.originId.trim()
        if (incOrigin.isNotBlank()) {
            val byOrigin = candidates.firstOrNull { cur ->
                cur.originId.equals(incOrigin, ignoreCase = false) || cur.id == incOrigin
            }
            if (byOrigin != null) return byOrigin
        }
        val incName = incoming.name.trim()
        if (incName.isBlank()) return null
        val byName = candidates.firstOrNull { cur -> cur.name.trim().equals(incName, ignoreCase = true) }
        if (byName != null) {
            // Do not allow an unrelated unprotected macro with a generic name to overwrite a locked/protected macro
            if (MacroSecurity.hasAnyProtection(byName) && !MacroSecurity.hasAnyProtection(incoming)) {
                return null
            }
            return byName
        }
        return null
    }

    /**
     * Imports either a single macro JSON, a single macro .zip bundle (with linked macros, logos & PNGs),
     * or a full backup .zip.
     * - If an imported macro matches an existing macro (by origin ID or name), overwrites it in-place
     *   and ADDS the incoming expiry duration onto any remaining active deadline.
     * - Preserves [RunMacroAction] links across both new and overwritten macros.
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
        for ((iconName, bytes) in decoded.macroIcons) {
            runCatching { saveMacroLogoBytes(iconName, bytes) }
        }
        for (script in decoded.scripts) {
            saveScript(script)
        }

        val now = effectiveNowMs()
        val existingSnapshot = _macros.value.toMutableList()
        val matchedPairs = ArrayList<Pair<Macro, Macro?>>()
        val finalIdMap = HashMap<String, String>()

        for (incoming in decoded.macros) {
            val match = findMatchingMacroForOverwrite(incoming, existingSnapshot)
            if (match != null) {
                existingSnapshot.removeAll { it.id == match.id }
                finalIdMap[incoming.id] = match.id
            } else {
                finalIdMap[incoming.id] = incoming.id
            }
            matchedPairs += incoming to match
        }

        var overwrittenCount = 0
        var extendedCount = 0
        for ((incoming, existing) in matchedPairs) {
            val relinked = incoming.copy(
                actions = incoming.actions.mapAllActions { a ->
                    if (a is RunMacroAction && a.targetMacroId in finalIdMap) {
                        a.copy(targetMacroId = finalIdMap[a.targetMacroId]!!)
                    } else a
                }
            )
            if (existing != null) {
                val res = MacroSecurity.mergeOverwrittenMacro(existing, relinked, now)
                saveMacro(res.merged)
                overwrittenCount++
                if (res.deadlineExtended) extendedCount++
                if (existing.logoImage.isNotBlank() && existing.logoImage != res.merged.logoImage) {
                    cleanupUnusedMacroIcon(existing.logoImage, excludingMacroId = res.merged.id)
                }
            } else {
                saveMacro(relinked)
            }
        }

        return ImportSummary(
            macrosImported = decoded.macros.size,
            scriptsImported = decoded.scripts.size,
            imagesImported = imagesCount,
            macrosOverwritten = overwrittenCount,
            deadlinesExtended = extendedCount
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
        private const val KEY_MAX_SEEN_EPOCH_MS = "max_seen_epoch_ms"

        @Volatile
        private var instance: LocalStore? = null

        fun get(context: Context): LocalStore =
            instance ?: synchronized(this) {
                instance ?: LocalStore(context.applicationContext).also { instance = it }
            }

        fun sanitizeFolderName(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9_\\- ]"), "").trim().replace(Regex("\\s+"), "_").take(36)

        fun normalizeImageName(raw: String): String {
            val cleaned = raw.trim().replace('\\', '/').trimStart('/')
            val slashIdx = cleaned.indexOf('/')
            val folderPart = if (slashIdx > 0) sanitizeFolderName(cleaned.substring(0, slashIdx)) else ""
            val filePart = cleaned.substringAfterLast('/')
            val base = sanitizeFileName(
                filePart
                    .removeSuffix(".png").removeSuffix(".PNG")
                    .removeSuffix(".jpg").removeSuffix(".JPG")
                    .removeSuffix(".jpeg").removeSuffix(".JPEG")
                    .removeSuffix(".webp").removeSuffix(".WEBP")
            ).ifBlank { "target" }
            return if (folderPart.isBlank()) "$base.png" else "$folderPart/$base.png"
        }

        fun sanitizeIconFileName(raw: String): String {
            val base = sanitizeFileName(
                raw.substringAfterLast('/').substringAfterLast('\\')
                    .removeSuffix(".png").removeSuffix(".PNG")
                    .removeSuffix(".jpg").removeSuffix(".JPG")
                    .removeSuffix(".jpeg").removeSuffix(".JPEG")
                    .removeSuffix(".webp").removeSuffix(".WEBP")
            )
            return if (base.isBlank()) "" else "$base.png"
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
