package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.DisplayMetrics
import android.util.LruCache
import android.view.WindowManager
import com.example.vision.PreparedTemplate
import com.example.vision.decodeFrame
import com.example.vision.savePng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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
    private val macroSetsDir = File(rootDir, "macro_sets").apply { mkdirs() }
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

    /** Bounded in-memory template cache (max 32 prepared templates) to prevent unbounded RAM growth. */
    private val templateCache = LruCache<String, PreparedTemplate>(MAX_CACHED_TEMPLATES)

    @Volatile
    private var cachedMaxSeenMs: Long = prefs.getLong(KEY_MAX_SEEN_EPOCH_MS, 0L)

    @Volatile
    private var lastPersistedMaxSeenMs: Long = cachedMaxSeenMs

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    private val _macroSets = MutableStateFlow<List<MacroSet>>(emptyList())
    val macroSets: StateFlow<List<MacroSet>> = _macroSets.asStateFlow()

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
        flushClockWatermark()
        reloadAll()
        seedExamplesIfFirstRun()
    }

    /**
     * Monotonic wall-clock watermark to prevent trivial device-clock rollback from bypassing
     * macro expiration deadlines offline. Uses in-memory cache and throttles disk persistence
     * to every 30s so tight macro execution loops incur zero SharedPreferences I/O.
     */
    fun effectiveNowMs(): Long {
        val sys = System.currentTimeMillis()
        val cur = cachedMaxSeenMs
        if (sys > cur) {
            cachedMaxSeenMs = sys
            if (sys - lastPersistedMaxSeenMs >= WATERMARK_PERSIST_INTERVAL_MS) {
                lastPersistedMaxSeenMs = sys
                prefs.edit().putLong(KEY_MAX_SEEN_EPOCH_MS, sys).apply()
            }
            return sys
        }
        return cur
    }

    /** Forces immediate persistence of the latest monotonic wall-clock watermark. */
    fun flushClockWatermark() {
        val cur = maxOf(cachedMaxSeenMs, System.currentTimeMillis())
        cachedMaxSeenMs = cur
        if (cur > lastPersistedMaxSeenMs) {
            lastPersistedMaxSeenMs = cur
            prefs.edit().putLong(KEY_MAX_SEEN_EPOCH_MS, cur).apply()
        }
    }

    fun reloadAll() {
        _macroSets.value = loadMacroSets()
        _macros.value = loadMacros()
        _scripts.value = loadScripts()
        _imageFolders.value = loadImageFolders()
        _images.value = loadImages()
        _settings.value = loadSettings()
    }

    /** Returns the device's current real physical display size (width x height in pixels). */
    fun currentScreenSize(): Pair<Int, Int> {
        return runCatching {
            val wm = appContext.getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
            }
        }.getOrElse {
            val dm = appContext.resources.displayMetrics
            dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }
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
        _macros.update { list ->
            (list.filterNot { it.id == updated.id } + updated).sortedByDescending { it.updatedAt }
        }
        flushClockWatermark()
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
        _macros.update { list -> list.filterNot { it.id == id } }
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
            actions = macro.actions.cloneWithRemappedIds(),
            stuckActions = macro.stuckActions.cloneWithRemappedIds(),
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

    fun cleanupUnusedMacroIcon(iconName: String, excludingMacroId: String = "", excludingSetId: String = "") {
        val clean = sanitizeIconFileName(iconName)
        if (clean.isBlank()) return
        val stillUsedByMacro = _macros.value.any { it.id != excludingMacroId && sanitizeIconFileName(it.logoImage) == clean }
        val stillUsedBySet = _macroSets.value.any { it.id != excludingSetId && sanitizeIconFileName(it.logoImage) == clean }
        if (!stillUsedByMacro && !stillUsedBySet) {
            File(macroIconsDir, clean).delete()
        }
    }

    // ---- Macro Sets ----

    private fun loadMacroSets(): List<MacroSet> {
        return macroSetsDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString<MacroSet>(file.readText()) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    fun saveMacroSet(set: MacroSet): MacroSet {
        val updated = set.copy(updatedAt = System.currentTimeMillis())
        val file = File(macroSetsDir, "${sanitizeFileName(updated.id)}.json")
        file.writeText(json.encodeToString(updated))
        _macroSets.update { list ->
            (list.filterNot { it.id == updated.id } + updated).sortedByDescending { it.updatedAt }
        }
        flushClockWatermark()
        return updated
    }

    fun getMacroSetById(id: String): MacroSet? {
        if (id.isBlank()) return null
        return _macroSets.value.firstOrNull { it.id == id }
            ?: File(macroSetsDir, "${sanitizeFileName(id)}.json")
                .takeIf { it.exists() }
                ?.let { f -> runCatching { json.decodeFromString<MacroSet>(f.readText()) }.getOrNull() }
    }

    fun getMacrosInSet(setId: String): List<Macro> =
        if (setId.isBlank()) _macros.value.filter { it.setId.isBlank() || getMacroSetById(it.setId) == null }
        else _macros.value.filter { it.setId == setId }

    /**
     * Saves updated security (Lock / Expiry / Password) on [updatedSet] and synchronizes lock, expiry,
     * and password across all member macros inside the set (preserving each member's Active/Passive state).
     */
    fun saveMacroSetSecurity(updatedSet: MacroSet): MacroSet {
        val saved = saveMacroSet(updatedSet)
        val members = _macros.value.filter { it.setId == saved.id }
        for (member in members) {
            saveMacro(MacroSecurity.syncMacroWithSetSecurity(member, saved))
        }
        return saved
    }

    /**
     * Updates which macros belong to [setId]. Newly added macros inherit lock/expiry when the set is protected.
     */
    fun setMacrosInSet(setId: String, selectedMacroIds: Set<String>) {
        val set = getMacroSetById(setId) ?: return
        val protectNew = MacroSecurity.hasAnySetProtection(set) && MacroSecurity.hasSetPassword(set)
        val snapshot = _macros.value.toList()
        for (m in snapshot) {
            val shouldBeInSet = m.id in selectedMacroIds
            when {
                shouldBeInSet && m.setId != set.id -> {
                    val moved = m.copy(setId = set.id)
                    saveMacro(if (protectNew) MacroSecurity.syncMacroWithSetSecurity(moved, set) else moved)
                }
                !shouldBeInSet && m.setId == set.id -> {
                    saveMacro(m.copy(setId = ""))
                }
            }
        }
        saveMacroSet(set)
    }

    /** Moves a single macro into [targetSetId] (or ungrouped when blank), applying set protection if needed. */
    fun moveMacroToSet(macroId: String, targetSetId: String): Macro? {
        val m = getMacroById(macroId) ?: return null
        val targetSet = if (targetSetId.isBlank()) null else getMacroSetById(targetSetId)
        val newSetId = targetSet?.id.orEmpty()
        var updated = m.copy(setId = newSetId)
        if (targetSet != null && MacroSecurity.hasAnySetProtection(targetSet) && MacroSecurity.hasSetPassword(targetSet)) {
            updated = MacroSecurity.syncMacroWithSetSecurity(updated, targetSet)
        }
        val saved = saveMacro(updated)
        if (targetSet != null) saveMacroSet(targetSet)
        return saved
    }

    /**
     * Duplicates [set] together with all its member macros, remapping internal [RunMacroAction] and
     * [FinishMacroAction] links so the duplicated set is completely self-contained.
     */
    fun duplicateMacroSet(set: MacroSet): MacroSet {
        val newSetId = newId()
        val copiedSetLogo = if (set.logoImage.isNotBlank()) {
            val src = macroIconFile(set.logoImage)
            if (src != null && src.exists()) {
                val destName = "logo_set_${sanitizeFileName(newSetId)}.png"
                runCatching {
                    src.copyTo(File(macroIconsDir, destName), overwrite = true)
                    destName
                }.getOrDefault("")
            } else ""
        } else ""

        val now = System.currentTimeMillis()
        val newSet = saveMacroSet(
            set.copy(
                id = newSetId,
                originId = "",
                name = "${set.name} (Salinan)",
                logoImage = copiedSetLogo,
                oneTimeImportToken = "",
                oneTimeImportSeal = "",
                createdAt = now,
                updatedAt = now
            )
        )

        val members = _macros.value.filter { it.setId == set.id }
        val idMap = HashMap<String, String>()
        members.forEach { m -> idMap[m.id] = newId() }
        for (m in members) {
            val newMid = idMap[m.id]!!
            val copiedLogo = if (m.logoImage.isNotBlank()) {
                val src = macroIconFile(m.logoImage)
                if (src != null && src.exists()) {
                    val destName = "logo_${sanitizeFileName(newMid)}.png"
                    runCatching {
                        src.copyTo(File(macroIconsDir, destName), overwrite = true)
                        destName
                    }.getOrDefault("")
                } else ""
            } else ""
            fun remapList(list: List<Action>) = list.cloneWithRemappedIds().mapAllActions { a ->
                when {
                    a is RunMacroAction && a.targetMacroId in idMap -> a.copy(targetMacroId = idMap[a.targetMacroId]!!)
                    a is FinishMacroAction && a.targetMacroId in idMap -> a.copy(targetMacroId = idMap[a.targetMacroId]!!)
                    else -> a
                }
            }
            saveMacro(
                m.copy(
                    id = newMid,
                    originId = "",
                    setId = newSet.id,
                    logoImage = copiedLogo,
                    oneTimeImportToken = "",
                    oneTimeImportSeal = "",
                    actions = remapList(m.actions),
                    stuckActions = remapList(m.stuckActions),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        return newSet
    }

    /**
     * Deletes a Macro Set. When [deleteMembers] is true, deletes all macros inside the set;
     * otherwise moves member macros back to ungrouped (`setId = ""`, keeping their individual locks intact).
     */
    fun deleteMacroSet(id: String, deleteMembers: Boolean = false) {
        val existing = getMacroSetById(id)
        val members = _macros.value.filter { it.setId == id }
        if (deleteMembers) {
            for (m in members) {
                deleteMacro(m.id)
            }
        } else {
            for (m in members) {
                saveMacro(m.copy(setId = ""))
            }
        }
        File(macroSetsDir, "${sanitizeFileName(id)}.json").delete()
        _macroSets.update { list -> list.filterNot { it.id == id } }
        if (existing != null && existing.logoImage.isNotBlank()) {
            cleanupUnusedMacroIcon(existing.logoImage, excludingSetId = id)
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
        _scripts.update { list ->
            (list.filterNot { it.id == updated.id } + updated).sortedByDescending { it.updatedAt }
        }
        return updated
    }

    fun deleteScript(id: String) {
        File(scriptsDir, "${sanitizeFileName(id)}.json").delete()
        _scripts.update { list -> list.filterNot { it.id == id } }
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
        fun rewriteList(list: List<Action>) = list.mapAllActions { a ->
            when (a) {
                is ImageAction -> if (normalizeImageName(a.imageName) == oldNormalized) a.copy(imageName = newNormalized) else a
                is TrackObjectAction -> {
                    val primary = if (normalizeImageName(a.imageName) == oldNormalized) newNormalized else a.imageName
                    val extras = a.extraImageNames.map { if (normalizeImageName(it) == oldNormalized) newNormalized else it }
                    if (primary != a.imageName || extras != a.extraImageNames) {
                        a.copy(imageName = primary, extraImageNames = extras)
                    } else a
                }
                else -> a
            }
        }
        for (m in _macros.value) {
            if (m.imageNames().any { normalizeImageName(it) == oldNormalized }) {
                val updated = m.copy(
                    actions = rewriteList(m.actions),
                    stuckActions = rewriteList(m.stuckActions)
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
        templateCache.get(normalized)?.let { return it }
        val file = resolveExistingImageFile(normalized) ?: return null
        val frame = file.decodeFrame() ?: return null
        val prepared = PreparedTemplate(normalized, frame)
        templateCache.put(normalized, prepared)
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

    private val consumedTokensLock = Any()
    private val consumedTokensFile = File(appContext.filesDir, "consumed_import_tokens.txt")

    private fun loadConsumedImportTokens(): MutableSet<String> = synchronized(consumedTokensLock) {
        val merged = LinkedHashSet<String>()
        prefs.getStringSet(KEY_CONSUMED_IMPORT_TOKENS, emptySet())
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.let { merged.addAll(it) }
        if (consumedTokensFile.exists()) {
            runCatching {
                consumedTokensFile.readLines(Charsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .let { merged.addAll(it) }
            }
        }
        merged
    }

    private fun markImportTokensConsumed(newTokens: Set<String>) = synchronized(consumedTokensLock) {
        if (newTokens.isEmpty()) return@synchronized
        val all = loadConsumedImportTokens()
        all.addAll(newTokens.map { it.trim() }.filter { it.isNotBlank() })
        prefs.edit().putStringSet(KEY_CONSUMED_IMPORT_TOKENS, HashSet(all)).commit()
        runCatching {
            val tmp = File(appContext.filesDir, "consumed_import_tokens.tmp")
            tmp.writeText(all.joinToString("\n"), Charsets.UTF_8)
            if (!tmp.renameTo(consumedTokensFile)) {
                consumedTokensFile.writeText(all.joinToString("\n"), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    data class ExportSummary(
        val macrosExported: Int,
        val imagesExported: Int,
        val singleUseImport: Boolean = false,
        val setsExported: Int = 0
    )

    private fun ensureExportSourceResolution(macro: Macro): Macro {
        if (macro.sourceWidth > 0 && macro.sourceHeight > 0) return macro
        val (dw, dh) = currentScreenSize()
        val (sw, sh) = ResolutionScaler.inferSourceSizeForDevice(macro, dw, dh)
        return if (sw > 0 && sh > 0) macro.copy(sourceWidth = sw, sourceHeight = sh) else macro
    }

    /**
     * Exports a macro, all linked sub-macros, and all PNG target images they reference
     * into a portable .zip bundle (macro.json, linked_macros folder, and images folder).
     * When [singleUseImport] is true, embeds a unique signed 1× import token into the exported ZIP.
     */
    fun exportMacroZip(
        macro: Macro,
        output: OutputStream,
        singleUseImport: Boolean = false
    ): ExportSummary {
        val latest = ensureExportSourceResolution(getMacroById(macro.id) ?: macro)
        val res = MacroBundleCodec.writeMacroZip(
            root = latest,
            lookupMacro = { id -> getMacroById(id)?.let(::ensureExportSourceResolution) },
            loadImage = { imgName ->
                resolveExistingImageFile(imgName)?.let { f -> relativeImageName(f) to f.readBytes() }
            },
            output = output,
            loadMacroIcon = { iconName ->
                macroIconFile(iconName)?.takeIf { it.exists() }?.let { f -> f.name to f.readBytes() }
            },
            singleUseImport = singleUseImport,
            exportedAtEpochMs = effectiveNowMs()
        )
        return ExportSummary(
            macrosExported = res.macrosExported,
            imagesExported = res.imagesExported,
            singleUseImport = res.singleUseImport
        )
    }

    /**
     * Exports a [MacroSet], all of its member macros, any externally linked sub-macros,
     * custom logos, and all referenced PNG target images into a single portable `.zip` bundle.
     */
    fun exportMacroSetZip(
        set: MacroSet,
        output: OutputStream,
        singleUseImport: Boolean = false
    ): ExportSummary {
        val latestSet = getMacroSetById(set.id) ?: set
        val members = _macros.value
            .filter { it.setId == latestSet.id }
            .map(::ensureExportSourceResolution)
        val res = MacroBundleCodec.writeMacroSetZip(
            set = latestSet,
            memberMacros = members,
            lookupMacro = { id -> getMacroById(id)?.let(::ensureExportSourceResolution) },
            loadImage = { imgName ->
                resolveExistingImageFile(imgName)?.let { f -> relativeImageName(f) to f.readBytes() }
            },
            output = output,
            loadMacroIcon = { iconName ->
                macroIconFile(iconName)?.takeIf { it.exists() }?.let { f -> f.name to f.readBytes() }
            },
            singleUseImport = singleUseImport,
            exportedAtEpochMs = effectiveNowMs()
        )
        return ExportSummary(
            macrosExported = res.macrosExported,
            imagesExported = res.imagesExported,
            singleUseImport = res.singleUseImport,
            setsExported = res.setsExported
        )
    }

    /** Exports every macro set, macro, Lua script, target image, and custom logo into a single backup .zip file. */
    fun exportAllBackupZip(output: OutputStream): ExportSummary {
        var imagesWritten = 0
        val writtenIcons = HashSet<String>()
        ZipOutputStream(output).use { zip ->
            for (set in _macroSets.value) {
                zip.putNextEntry(ZipEntry("macro_sets/${sanitizeFileName(set.id)}.json"))
                zip.write(json.encodeToString(set).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (set.logoImage.isNotBlank()) {
                    val f = macroIconFile(set.logoImage)
                    if (f != null && f.exists() && writtenIcons.add(f.name)) {
                        zip.putNextEntry(ZipEntry("macro_icons/${f.name}"))
                        zip.write(f.readBytes())
                        zip.closeEntry()
                    }
                }
            }
            for (rawMacro in _macros.value) {
                val macro = ensureExportSourceResolution(rawMacro)
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
        return ExportSummary(
            macrosExported = _macros.value.size,
            imagesExported = imagesWritten,
            setsExported = _macroSets.value.size
        )
    }

    data class ImportSummary(
        val macrosImported: Int,
        val scriptsImported: Int,
        val imagesImported: Int,
        val macrosOverwritten: Int = 0,
        val deadlinesExtended: Int = 0,
        val singleUseImport: Boolean = false,
        val setsImported: Int = 0,
        val setsOverwritten: Int = 0
    )

    private fun findMatchingMacroSetForOverwrite(incoming: MacroSet, candidates: List<MacroSet>): MacroSet? {
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
            if (MacroSecurity.hasAnySetProtection(byName) && !MacroSecurity.hasAnySetProtection(incoming)) {
                return null
            }
            return byName
        }
        return null
    }

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
     * Imports a single macro JSON, a single macro .zip bundle, a Macro Set .zip bundle,
     * or a full backup .zip.
     * - Rejects tampered or already-used single-use (1× import) bundles before writing anything.
     * - Overwrites matching Macro Sets & macros in-place and stacks active expiry deadlines.
     * - Preserves [RunMacroAction], [FinishMacroAction], and [Macro.setId] links across all macros.
     */
    fun importFromStream(input: InputStream): ImportSummary {
        val decoded = MacroBundleCodec.readBundle(input)
        // Enforce single-use (1× import) token check BEFORE saving any image, script, set, or macro
        val oneTimeTokens = MacroBundleCodec.validateAndExtractOneTimeTokens(
            decoded = decoded,
            alreadyConsumedTokens = loadConsumedImportTokens()
        )

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
        var setsOverwrittenCount = 0
        var extendedCount = 0

        // 1. Import / merge Macro Sets first so incoming macros can resolve their final setId
        val existingSetsSnapshot = _macroSets.value.toMutableList()
        val finalSetIdMap = HashMap<String, String>()
        for (incomingSet in decoded.macroSets) {
            val match = findMatchingMacroSetForOverwrite(incomingSet, existingSetsSnapshot)
            if (match != null) {
                existingSetsSnapshot.removeAll { it.id == match.id }
                val res = MacroSecurity.mergeOverwrittenMacroSet(match, incomingSet, now)
                saveMacroSet(res.merged)
                finalSetIdMap[incomingSet.id] = res.merged.id
                setsOverwrittenCount++
                if (res.deadlineExtended) extendedCount++
                if (match.logoImage.isNotBlank() && match.logoImage != res.merged.logoImage) {
                    cleanupUnusedMacroIcon(match.logoImage, excludingSetId = res.merged.id)
                }
            } else {
                saveMacroSet(incomingSet)
                finalSetIdMap[incomingSet.id] = incomingSet.id
            }
        }

        // 2. Import / merge Macros with relinked macro IDs and set IDs
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
        for ((incoming, existing) in matchedPairs) {
            fun relinkList(list: List<Action>): List<Action> = list.mapAllActions { a ->
                when {
                    a is RunMacroAction && a.targetMacroId in finalIdMap ->
                        a.copy(targetMacroId = finalIdMap[a.targetMacroId]!!)
                    a is FinishMacroAction && a.targetMacroId in finalIdMap ->
                        a.copy(targetMacroId = finalIdMap[a.targetMacroId]!!)
                    else -> a
                }
            }
            val resolvedSetId = if (incoming.setId.isNotBlank()) finalSetIdMap[incoming.setId].orEmpty() else ""
            val relinked = incoming.copy(
                setId = resolvedSetId,
                actions = relinkList(incoming.actions),
                stuckActions = relinkList(incoming.stuckActions)
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

        if (oneTimeTokens.isNotEmpty()) {
            markImportTokensConsumed(oneTimeTokens)
        }

        return ImportSummary(
            macrosImported = decoded.macros.size,
            scriptsImported = decoded.scripts.size,
            imagesImported = imagesCount,
            macrosOverwritten = overwrittenCount,
            deadlinesExtended = extendedCount,
            singleUseImport = oneTimeTokens.isNotEmpty(),
            setsImported = decoded.macroSets.size,
            setsOverwritten = setsOverwrittenCount
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
        private const val KEY_CONSUMED_IMPORT_TOKENS = "consumed_one_time_import_tokens_v1"
        private const val MAX_CACHED_TEMPLATES = 32
        private const val WATERMARK_PERSIST_INTERVAL_MS = 30_000L

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
