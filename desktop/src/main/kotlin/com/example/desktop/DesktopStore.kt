package com.example.desktop

import com.example.data.Action
import com.example.data.ClickAction
import com.example.data.FinishMacroAction
import com.example.data.ImageAction
import com.example.data.LuaScript
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.ResolutionScaler
import com.example.data.RunMacroAction
import com.example.data.SwipeAction
import com.example.data.TrackObjectAction
import com.example.data.WaitAction
import com.example.data.cloneWithRemappedIds
import com.example.data.imageNames
import com.example.data.mapAllActions
import com.example.data.newId
import com.example.vision.Frame
import com.example.vision.PreparedTemplate
import com.example.vision.ReusableFramePool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Fast zero-overhead BufferedImage -> Frame converter using DataBufferInt when possible. */
fun bufferedImageToFrame(img: BufferedImage, pool: ReusableFramePool? = null): Frame {
    val w = img.width.coerceAtLeast(1)
    val h = img.height.coerceAtLeast(1)
    val count = w * h
    val pixels = pool?.acquire(count) ?: IntArray(count)
    if ((img.type == BufferedImage.TYPE_INT_ARGB || img.type == BufferedImage.TYPE_INT_RGB) &&
        img.raster.dataBuffer is DataBufferInt
    ) {
        val raw = (img.raster.dataBuffer as DataBufferInt).data
        if (raw.size >= count) {
            System.arraycopy(raw, 0, pixels, 0, count)
            if (img.type == BufferedImage.TYPE_INT_RGB) {
                for (i in 0 until count) pixels[i] = pixels[i] or (0xFF shl 24)
            }
            return Frame(w, h, pixels)
        }
    }
    img.getRGB(0, 0, w, h, pixels, 0, w)
    return Frame(w, h, pixels)
}

/**
 * Pure-JVM Desktop repository for Wjust macro PC:
 * - 100% file & ZIP format compatible with Android Wjust macro (bidirectional Macro & Macro Set .zip export/import!)
 * - Stores data in `~/.wjust-macro/` (or `./wjust-macro-data/` in portable mode)
 * - Bounded thread-safe LRU template cache (64 templates) + monotonic clock watermark + 1× import token persistence
 */
class DesktopStore(
    val rootDir: File = resolveDefaultDataDir()
) {
    private val macrosDir = File(rootDir, "macros").apply { mkdirs() }
    private val macroSetsDir = File(rootDir, "macro_sets").apply { mkdirs() }
    private val scriptsDir = File(rootDir, "scripts").apply { mkdirs() }
    private val imagesDir = File(rootDir, "images").apply { mkdirs() }
    private val macroIconsDir = File(rootDir, "macro_icons").apply { mkdirs() }
    private val settingsFile = File(rootDir, "desktop_settings.json")
    private val watermarkFile = File(rootDir, "clock_watermark.dat")
    private val consumedTokensFile = File(rootDir, "consumed_import_tokens.txt")

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private val templateCacheLock = Any()
    private val templateCache = object : LinkedHashMap<String, PreparedTemplate>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedTemplate>?): Boolean =
            size > MAX_CACHED_TEMPLATES
    }

    @Volatile
    private var cachedMaxSeenMs: Long = runCatching {
        if (watermarkFile.exists()) watermarkFile.readText().trim().toLongOrNull() ?: 0L else 0L
    }.getOrDefault(0L)

    @Volatile
    private var lastPersistedMaxSeenMs: Long = cachedMaxSeenMs

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    private val _macroSets = MutableStateFlow<List<MacroSet>>(emptyList())
    val macroSets: StateFlow<List<MacroSet>> = _macroSets.asStateFlow()

    private val _scripts = MutableStateFlow<List<LuaScript>>(emptyList())
    val scripts: StateFlow<List<LuaScript>> = _scripts.asStateFlow()

    private val _images = MutableStateFlow<List<DesktopImageAsset>>(emptyList())
    val images: StateFlow<List<DesktopImageAsset>> = _images.asStateFlow()

    private val _imageFolders = MutableStateFlow<List<String>>(emptyList())
    val imageFolders: StateFlow<List<String>> = _imageFolders.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    init {
        rootDir.mkdirs()
        effectiveNowMs()
        flushClockWatermark()
        reloadAll()
        seedExamplesIfEmpty()
    }

    fun effectiveNowMs(): Long {
        val sys = System.currentTimeMillis()
        val cur = cachedMaxSeenMs
        if (sys > cur) {
            cachedMaxSeenMs = sys
            if (sys - lastPersistedMaxSeenMs >= WATERMARK_PERSIST_INTERVAL_MS) {
                lastPersistedMaxSeenMs = sys
                runCatching { watermarkFile.writeText(sys.toString()) }
            }
            return sys
        }
        return cur
    }

    fun flushClockWatermark() {
        val cur = maxOf(cachedMaxSeenMs, System.currentTimeMillis())
        cachedMaxSeenMs = cur
        if (cur > lastPersistedMaxSeenMs) {
            lastPersistedMaxSeenMs = cur
            runCatching { watermarkFile.writeText(cur.toString()) }
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

    fun currentDesktopScreenSize(): Pair<Int, Int> = runCatching {
        val d = Toolkit.getDefaultToolkit().screenSize
        d.width.coerceAtLeast(1) to d.height.coerceAtLeast(1)
    }.getOrElse { 1920 to 1080 }

    // ---- Settings ----

    private fun loadSettings(): DesktopSettings {
        if (!settingsFile.exists()) return DesktopSettings()
        return runCatching { json.decodeFromString<DesktopSettings>(settingsFile.readText()) }
            .getOrDefault(DesktopSettings())
    }

    fun saveSettings(next: DesktopSettings) {
        runCatching { settingsFile.writeText(json.encodeToString(next)) }
        _settings.value = next
    }

    // ---- Macros ----

    private fun loadMacros(): List<Macro> =
        macrosDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString<Macro>(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

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
        val newMid = newId()
        val copiedLogo = if (macro.logoImage.isNotBlank()) {
            val src = macroIconFile(macro.logoImage)
            if (src != null && src.exists()) {
                val destName = "logo_${sanitizeFileName(newMid)}.png"
                runCatching {
                    src.copyTo(File(macroIconsDir, destName), overwrite = true)
                    destName
                }.getOrDefault("")
            } else ""
        } else ""
        return saveMacro(
            macro.copy(
                id = newMid,
                originId = "",
                name = "${macro.name} (Salinan)",
                logoImage = copiedLogo,
                oneTimeImportToken = "",
                oneTimeImportSeal = "",
                actions = macro.actions.cloneWithRemappedIds(),
                stuckActions = macro.stuckActions.cloneWithRemappedIds(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // ---- Macro Sets ----

    private fun loadMacroSets(): List<MacroSet> =
        macroSetsDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString<MacroSet>(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

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

    fun saveMacroSetSecurity(updatedSet: MacroSet): MacroSet {
        val saved = saveMacroSet(updatedSet)
        for (member in _macros.value.filter { it.setId == saved.id }) {
            saveMacro(MacroSecurity.syncMacroWithSetSecurity(member, saved))
        }
        return saved
    }

    fun setMacrosInSet(setId: String, selectedMacroIds: Set<String>) {
        val set = getMacroSetById(setId) ?: return
        val protectNew = MacroSecurity.hasAnySetProtection(set) && MacroSecurity.hasSetPassword(set)
        for (m in _macros.value.toList()) {
            val shouldBeIn = m.id in selectedMacroIds
            when {
                shouldBeIn && m.setId != set.id -> {
                    val moved = m.copy(setId = set.id)
                    saveMacro(if (protectNew) MacroSecurity.syncMacroWithSetSecurity(moved, set) else moved)
                }
                !shouldBeIn && m.setId == set.id -> saveMacro(m.copy(setId = ""))
            }
        }
        saveMacroSet(set)
    }

    fun moveMacroToSet(macroId: String, targetSetId: String): Macro? {
        val m = getMacroById(macroId) ?: return null
        val targetSet = if (targetSetId.isBlank()) null else getMacroSetById(targetSetId)
        var updated = m.copy(setId = targetSet?.id.orEmpty())
        if (targetSet != null && MacroSecurity.hasAnySetProtection(targetSet) && MacroSecurity.hasSetPassword(targetSet)) {
            updated = MacroSecurity.syncMacroWithSetSecurity(updated, targetSet)
        }
        val saved = saveMacro(updated)
        if (targetSet != null) saveMacroSet(targetSet)
        return saved
    }

    fun duplicateMacroSet(set: MacroSet): MacroSet {
        val newSetId = newId()
        val now = System.currentTimeMillis()
        val newSet = saveMacroSet(
            set.copy(
                id = newSetId,
                originId = "",
                name = "${set.name} (Salinan)",
                oneTimeImportToken = "",
                oneTimeImportSeal = "",
                createdAt = now,
                updatedAt = now
            )
        )
        val members = _macros.value.filter { it.setId == set.id }
        val idMap = members.associate { it.id to newId() }
        for (m in members) {
            val newMid = idMap[m.id]!!
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

    fun deleteMacroSet(id: String, deleteMembers: Boolean = false) {
        val existing = getMacroSetById(id)
        val members = _macros.value.filter { it.setId == id }
        if (deleteMembers) {
            members.forEach { deleteMacro(it.id) }
        } else {
            members.forEach { saveMacro(it.copy(setId = "")) }
        }
        File(macroSetsDir, "${sanitizeFileName(id)}.json").delete()
        _macroSets.update { list -> list.filterNot { it.id == id } }
        if (existing != null && existing.logoImage.isNotBlank()) {
            cleanupUnusedMacroIcon(existing.logoImage, excludingSetId = id)
        }
    }

    // ---- Scripts ----

    private fun loadScripts(): List<LuaScript> =
        scriptsDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString<LuaScript>(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun saveScript(script: LuaScript): LuaScript {
        val updated = script.copy(updatedAt = System.currentTimeMillis())
        File(scriptsDir, "${sanitizeFileName(updated.id)}.json").writeText(json.encodeToString(updated))
        _scripts.update { list ->
            (list.filterNot { it.id == updated.id } + updated).sortedByDescending { it.updatedAt }
        }
        return updated
    }

    fun deleteScript(id: String) {
        File(scriptsDir, "${sanitizeFileName(id)}.json").delete()
        _scripts.update { list -> list.filterNot { it.id == id } }
    }

    // ---- Images & Folders ----

    private fun loadImageFolders(): List<String> =
        imagesDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .map { it.name }
            .sortedBy { it.lowercase() }

    private fun refreshImagesAndFolders() {
        _imageFolders.value = loadImageFolders()
        _images.value = loadImages()
    }

    private fun scanPngsIn(dir: File, folderName: String): List<DesktopImageAsset> =
        dir.listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file ->
                val img = runCatching { ImageIO.read(file) }.getOrNull() ?: return@mapNotNull null
                val relName = if (folderName.isBlank()) file.name else "$folderName/${file.name}"
                DesktopImageAsset(
                    name = relName,
                    path = file.absolutePath,
                    width = img.width,
                    height = img.height,
                    sizeBytes = file.length(),
                    updatedAt = file.lastModified(),
                    folder = folderName
                )
            }

    private fun loadImages(): List<DesktopImageAsset> {
        val all = ArrayList<DesktopImageAsset>()
        all += scanPngsIn(imagesDir, "")
        imagesDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .forEach { sub -> all += scanPngsIn(sub, sub.name) }
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

    fun saveImage(desiredName: String, image: BufferedImage, folder: String = ""): DesktopImageAsset {
        val name = uniqueImageName(desiredName, folder)
        val file = File(imagesDir, name)
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
        synchronized(templateCacheLock) { templateCache.remove(name) }
        refreshImagesAndFolders()
        return _images.value.first { it.name == name }
    }

    fun saveImageBytes(desiredName: String, bytes: ByteArray): String {
        val normalized = normalizeImageName(desiredName)
        val file = File(imagesDir, normalized)
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalArgumentException("Format gambar tidak valid.")
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
        synchronized(templateCacheLock) { templateCache.remove(normalized) }
        refreshImagesAndFolders()
        return normalized
    }

    fun deleteImage(name: String) {
        val norm = normalizeImageName(name)
        File(imagesDir, norm).delete()
        synchronized(templateCacheLock) { templateCache.remove(norm) }
        refreshImagesAndFolders()
    }

    fun renameImage(oldName: String, newRawName: String): String? {
        val oldNorm = normalizeImageName(oldName)
        val oldFile = File(imagesDir, oldNorm)
        if (!oldFile.exists()) return null
        val folder = oldNorm.substringBefore('/', missingDelimiterValue = "")
        val newNorm = uniqueImageName(newRawName.substringAfterLast('/'), folder)
        val newFile = File(imagesDir, newNorm)
        newFile.parentFile?.mkdirs()
        if (!oldFile.renameTo(newFile)) return null
        synchronized(templateCacheLock) {
            templateCache.remove(oldNorm)
            templateCache.remove(newNorm)
        }
        rewriteMacroImageReference(oldNorm, newNorm)
        refreshImagesAndFolders()
        return newNorm
    }

    private fun rewriteMacroImageReference(oldNorm: String, newNorm: String) {
        if (oldNorm == newNorm) return
        fun rewriteList(list: List<Action>) = list.mapAllActions { a ->
            when (a) {
                is ImageAction -> if (normalizeImageName(a.imageName) == oldNorm) a.copy(imageName = newNorm) else a
                is TrackObjectAction -> {
                    val primary = if (normalizeImageName(a.imageName) == oldNorm) newNorm else a.imageName
                    val extras = a.extraImageNames.map { if (normalizeImageName(it) == oldNorm) newNorm else it }
                    if (primary != a.imageName || extras != a.extraImageNames) {
                        a.copy(imageName = primary, extraImageNames = extras)
                    } else a
                }
                else -> a
            }
        }
        for (m in _macros.value) {
            if (m.imageNames().any { normalizeImageName(it) == oldNorm }) {
                saveMacro(m.copy(actions = rewriteList(m.actions), stuckActions = rewriteList(m.stuckActions)))
            }
        }
    }

    fun resolveExistingImageFile(name: String): File? {
        val normalized = File(imagesDir, normalizeImageName(name))
        if (normalized.exists()) return normalized
        val raw = File(imagesDir, name.substringAfterLast('/').substringAfterLast('\\'))
        return if (raw.exists()) raw else null
    }

    fun loadTemplate(name: String): PreparedTemplate? {
        val normalized = normalizeImageName(name)
        synchronized(templateCacheLock) { templateCache[normalized]?.let { return it } }
        val file = resolveExistingImageFile(normalized) ?: return null
        val img = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        val prepared = PreparedTemplate(normalized, bufferedImageToFrame(img))
        synchronized(templateCacheLock) { templateCache[normalized] = prepared }
        return prepared
    }

    fun macroIconFile(iconName: String): File? {
        val clean = sanitizeIconFileName(iconName)
        return if (clean.isBlank()) null else File(macroIconsDir, clean)
    }

    fun saveMacroLogoBytes(rawFileName: String, bytes: ByteArray): String? {
        val clean = sanitizeIconFileName(rawFileName)
        if (clean.isBlank()) return null
        val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val target = File(macroIconsDir, clean)
        val size = minOf(img.width, img.height, 256).coerceAtLeast(1)
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, size, size, null)
        g.dispose()
        ImageIO.write(scaled, "png", target)
        return clean
    }

    fun cleanupUnusedMacroIcon(iconName: String, excludingMacroId: String = "", excludingSetId: String = "") {
        val clean = sanitizeIconFileName(iconName)
        if (clean.isBlank()) return
        val usedByMacro = _macros.value.any { it.id != excludingMacroId && sanitizeIconFileName(it.logoImage) == clean }
        val usedBySet = _macroSets.value.any { it.id != excludingSetId && sanitizeIconFileName(it.logoImage) == clean }
        if (!usedByMacro && !usedBySet) File(macroIconsDir, clean).delete()
    }

    private fun relativeImageName(file: File): String {
        val parent = file.parentFile
        return if (parent != null && parent != imagesDir && parent.parentFile == imagesDir) {
            "${parent.name}/${file.name}"
        } else file.name
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

    // ---- Export / Import (100% compatible with Android Wjust macro!) ----

    private val tokensLock = Any()

    private fun loadConsumedTokens(): MutableSet<String> = synchronized(tokensLock) {
        if (!consumedTokensFile.exists()) return LinkedHashSet()
        runCatching {
            consumedTokensFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        }.getOrDefault(LinkedHashSet())
    }

    private fun markTokensConsumed(newTokens: Set<String>) = synchronized(tokensLock) {
        if (newTokens.isEmpty()) return@synchronized
        val all = loadConsumedTokens()
        all.addAll(newTokens.map { it.trim() }.filter { it.isNotBlank() })
        runCatching { consumedTokensFile.writeText(all.joinToString("\n"), Charsets.UTF_8) }
    }

    data class ExportSummary(
        val macrosExported: Int,
        val imagesExported: Int,
        val singleUseImport: Boolean = false,
        val setsExported: Int = 0
    )

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

    private fun ensureExportSourceResolution(macro: Macro): Macro {
        if (macro.sourceWidth > 0 && macro.sourceHeight > 0) return macro
        val (dw, dh) = currentDesktopScreenSize()
        val (sw, sh) = ResolutionScaler.inferSourceSizeForDevice(macro, dw, dh)
        return if (sw > 0 && sh > 0) macro.copy(sourceWidth = sw, sourceHeight = sh) else macro
    }

    fun exportMacroZip(macro: Macro, output: OutputStream, singleUseImport: Boolean = false): ExportSummary {
        val latest = ensureExportSourceResolution(getMacroById(macro.id) ?: macro)
        val res = MacroBundleCodec.writeMacroZip(
            root = latest,
            lookupMacro = { id -> getMacroById(id)?.let(::ensureExportSourceResolution) },
            loadImage = { imgName -> resolveExistingImageFile(imgName)?.let { f -> relativeImageName(f) to f.readBytes() } },
            output = output,
            loadMacroIcon = { iconName -> macroIconFile(iconName)?.takeIf { it.exists() }?.let { f -> f.name to f.readBytes() } },
            singleUseImport = singleUseImport,
            exportedAtEpochMs = effectiveNowMs()
        )
        return ExportSummary(res.macrosExported, res.imagesExported, res.singleUseImport, res.setsExported)
    }

    fun exportMacroSetZip(set: MacroSet, output: OutputStream, singleUseImport: Boolean = false): ExportSummary {
        val latestSet = getMacroSetById(set.id) ?: set
        val members = _macros.value.filter { it.setId == latestSet.id }.map(::ensureExportSourceResolution)
        val res = MacroBundleCodec.writeMacroSetZip(
            set = latestSet,
            memberMacros = members,
            lookupMacro = { id -> getMacroById(id)?.let(::ensureExportSourceResolution) },
            loadImage = { imgName -> resolveExistingImageFile(imgName)?.let { f -> relativeImageName(f) to f.readBytes() } },
            output = output,
            loadMacroIcon = { iconName -> macroIconFile(iconName)?.takeIf { it.exists() }?.let { f -> f.name to f.readBytes() } },
            singleUseImport = singleUseImport,
            exportedAtEpochMs = effectiveNowMs()
        )
        return ExportSummary(res.macrosExported, res.imagesExported, res.singleUseImport, res.setsExported)
    }

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
                val m = ensureExportSourceResolution(rawMacro)
                zip.putNextEntry(ZipEntry("macros/${sanitizeFileName(m.id)}.json"))
                zip.write(json.encodeToString(m).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (m.logoImage.isNotBlank()) {
                    val f = macroIconFile(m.logoImage)
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
        return ExportSummary(_macros.value.size, imagesWritten, setsExported = _macroSets.value.size)
    }

    private fun findMatchingSet(incoming: MacroSet, candidates: List<MacroSet>): MacroSet? {
        val incOrigin = incoming.originId.trim()
        if (incOrigin.isNotBlank()) {
            candidates.firstOrNull { it.originId == incOrigin || it.id == incOrigin }?.let { return it }
        }
        val incName = incoming.name.trim()
        if (incName.isBlank()) return null
        val byName = candidates.firstOrNull { it.name.trim().equals(incName, ignoreCase = true) } ?: return null
        if (MacroSecurity.hasAnySetProtection(byName) && !MacroSecurity.hasAnySetProtection(incoming)) return null
        return byName
    }

    private fun findMatchingMacro(incoming: Macro, candidates: List<Macro>): Macro? {
        val incOrigin = incoming.originId.trim()
        if (incOrigin.isNotBlank()) {
            candidates.firstOrNull { it.originId == incOrigin || it.id == incOrigin }?.let { return it }
        }
        val incName = incoming.name.trim()
        if (incName.isBlank()) return null
        val byName = candidates.firstOrNull { it.name.trim().equals(incName, ignoreCase = true) } ?: return null
        if (MacroSecurity.hasAnyProtection(byName) && !MacroSecurity.hasAnyProtection(incoming)) return null
        return byName
    }

    fun importFromStream(input: InputStream): ImportSummary {
        val decoded = MacroBundleCodec.readBundle(input)
        val oneTimeTokens = MacroBundleCodec.validateAndExtractOneTimeTokens(decoded, loadConsumedTokens())

        var imagesCount = 0
        for ((fileName, bytes) in decoded.images) {
            runCatching { saveImageBytes(fileName, bytes); imagesCount++ }
        }
        for ((iconName, bytes) in decoded.macroIcons) {
            runCatching { saveMacroLogoBytes(iconName, bytes) }
        }
        for (script in decoded.scripts) saveScript(script)

        val now = effectiveNowMs()
        var setsOverwritten = 0
        var extended = 0

        val existingSets = _macroSets.value.toMutableList()
        val finalSetMap = HashMap<String, String>()
        for (incomingSet in decoded.macroSets) {
            val match = findMatchingSet(incomingSet, existingSets)
            if (match != null) {
                existingSets.removeAll { it.id == match.id }
                val res = MacroSecurity.mergeOverwrittenMacroSet(match, incomingSet, now)
                saveMacroSet(res.merged)
                finalSetMap[incomingSet.id] = res.merged.id
                setsOverwritten++
                if (res.deadlineExtended) extended++
            } else {
                saveMacroSet(incomingSet)
                finalSetMap[incomingSet.id] = incomingSet.id
            }
        }

        val existingMacros = _macros.value.toMutableList()
        val pairs = ArrayList<Pair<Macro, Macro?>>()
        val finalIdMap = HashMap<String, String>()
        for (incoming in decoded.macros) {
            val match = findMatchingMacro(incoming, existingMacros)
            if (match != null) {
                existingMacros.removeAll { it.id == match.id }
                finalIdMap[incoming.id] = match.id
            } else {
                finalIdMap[incoming.id] = incoming.id
            }
            pairs += incoming to match
        }

        var macrosOverwritten = 0
        for ((incoming, existing) in pairs) {
            fun relinkList(list: List<Action>): List<Action> = list.mapAllActions { a ->
                when {
                    a is RunMacroAction && a.targetMacroId in finalIdMap -> a.copy(targetMacroId = finalIdMap[a.targetMacroId]!!)
                    a is FinishMacroAction && a.targetMacroId in finalIdMap -> a.copy(targetMacroId = finalIdMap[a.targetMacroId]!!)
                    else -> a
                }
            }
            val resolvedSetId = if (incoming.setId.isNotBlank()) finalSetMap[incoming.setId].orEmpty() else ""
            val relinked = incoming.copy(
                setId = resolvedSetId,
                actions = relinkList(incoming.actions),
                stuckActions = relinkList(incoming.stuckActions)
            )
            if (existing != null) {
                val res = MacroSecurity.mergeOverwrittenMacro(existing, relinked, now)
                saveMacro(res.merged)
                macrosOverwritten++
                if (res.deadlineExtended) extended++
            } else {
                saveMacro(relinked)
            }
        }

        if (oneTimeTokens.isNotEmpty()) markTokensConsumed(oneTimeTokens)

        return ImportSummary(
            macrosImported = decoded.macros.size,
            scriptsImported = decoded.scripts.size,
            imagesImported = imagesCount,
            macrosOverwritten = macrosOverwritten,
            deadlinesExtended = extended,
            singleUseImport = oneTimeTokens.isNotEmpty(),
            setsImported = decoded.macroSets.size,
            setsOverwritten = setsOverwritten
        )
    }

    private fun seedExamplesIfEmpty() {
        val marker = File(rootDir, ".seeded_pc_v1")
        if (marker.exists()) return
        if (_macros.value.isEmpty()) {
            saveMacro(
                Macro(
                    name = "Contoh Auto Clicker PC",
                    loopCount = 5,
                    loopDelayMs = 500,
                    jitterPx = 3,
                    sourceWidth = 1920,
                    sourceHeight = 1080,
                    actions = listOf(
                        ClickAction(x = 960, y = 540, durationMs = 50, delayAfterMs = 350),
                        WaitAction(durationMs = 400, randomMs = 150),
                        SwipeAction(x1 = 960, y1 = 680, x2 = 960, y2 = 400, durationMs = 280, delayAfterMs = 350)
                    )
                )
            )
        }
        if (_scripts.value.isEmpty()) {
            saveScript(
                LuaScript(
                    name = "Contoh Lua PC",
                    code = DEFAULT_LUA_SAMPLE
                )
            )
        }
        runCatching { marker.writeText("1") }
    }

    companion object {
        private const val MAX_CACHED_TEMPLATES = 64
        private const val WATERMARK_PERSIST_INTERVAL_MS = 30_000L

        fun resolveDefaultDataDir(): File {
            val portableDir = File("wjust-macro-data")
            if (File("portable.flag").exists() || portableDir.exists()) return portableDir
            return File(System.getProperty("user.home"), ".wjust-macro")
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
-- Contoh Macro Lua Wjust macro PC
local w, h = getScreenSize()
log("Resolusi target PC: " .. w .. "x" .. h)

for i = 1, 5 do
    if not isRunning() then break end
    local ok, x, y, score = waitImage("tombol_play.png", 0.85, 3000)
    if ok then
        log("Ditemukan di (" .. x .. ", " .. y .. ") skor=" .. string.format("%.0f%%", score * 100))
        click(x, y)
        sleep(800)
    else
        log("Belum muncul (loop #" .. i .. ")")
        sleep(400)
    end
end
log("Selesai!")
        """.trimIndent()
    }
}
