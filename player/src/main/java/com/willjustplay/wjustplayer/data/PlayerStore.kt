package com.willjustplay.wjustplayer.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.data.Action
import com.example.data.FinishMacroAction
import com.example.data.Macro
import com.example.data.MacroBundleCodec
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.data.RunMacroAction
import com.example.data.mapAllActions
import com.example.vision.PreparedTemplate
import com.willjustplay.wjustplayer.runtime.decodeFrame
import com.willjustplay.wjustplayer.runtime.savePng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * Strictly Play-Only local repository for Wjust Player.
 *
 * Capabilities:
 * - Imports ONLY AES-256-GCM encrypted `.wjm` bundles via [MacroBundleCodec.readEncryptedWjm].
 * - Enforces single-use (1× import) tokens, expiry deadlines, overwrite stacking, and monotonic clock watermark.
 * - Allows toggling passive sub-macro ON/OFF and deleting imported macros/sets.
 * - Intentionally omits ALL macro creation, action editing, settings modification, image management, and export APIs.
 */
class PlayerStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, "wjustplayer")
    private val macrosDir = File(rootDir, "macros").apply { mkdirs() }
    private val macroSetsDir = File(rootDir, "macro_sets").apply { mkdirs() }
    private val imagesDir = File(rootDir, "images").apply { mkdirs() }
    private val macroIconsDir = File(rootDir, "macro_icons").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("wjustplayer_prefs", Context.MODE_PRIVATE)
    private val consumedTokensLock = Any()
    private val consumedTokensFile = File(appContext.filesDir, "consumed_wjm_tokens_v1.txt")

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private val templateCache = LruCache<String, PreparedTemplate>(MAX_CACHED_TEMPLATES)

    @Volatile
    private var cachedMaxSeenMs: Long = prefs.getLong(KEY_MAX_SEEN_EPOCH_MS, 0L)

    @Volatile
    private var lastPersistedMaxSeenMs: Long = cachedMaxSeenMs

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    private val _macroSets = MutableStateFlow<List<MacroSet>>(emptyList())
    val macroSets: StateFlow<List<MacroSet>> = _macroSets.asStateFlow()

    data class ImportSummary(
        val macrosImported: Int,
        val setsImported: Int,
        val macrosOverwritten: Int,
        val setsOverwritten: Int,
        val deadlinesExtended: Int,
        val singleUseImport: Boolean
    )

    init {
        effectiveNowMs()
        flushClockWatermark()
        reloadAll()
    }

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
    }

    private fun loadMacros(): List<Macro> =
        macrosDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString<Macro>(file.readText()) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    private fun loadMacroSets(): List<MacroSet> =
        macroSetsDir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString<MacroSet>(file.readText()) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    private fun persistMacro(macro: Macro): Macro {
        val updated = macro.copy(updatedAt = System.currentTimeMillis())
        val file = File(macrosDir, "${sanitizeFileName(updated.id)}.json")
        file.writeText(json.encodeToString(updated))
        _macros.update { list ->
            (list.filterNot { it.id == updated.id } + updated).sortedByDescending { it.updatedAt }
        }
        flushClockWatermark()
        return updated
    }

    private fun persistMacroSet(set: MacroSet): MacroSet {
        val updated = set.copy(updatedAt = System.currentTimeMillis())
        val file = File(macroSetsDir, "${sanitizeFileName(updated.id)}.json")
        file.writeText(json.encodeToString(updated))
        _macroSets.update { list ->
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

    fun getMacroSetById(id: String): MacroSet? {
        if (id.isBlank()) return null
        return _macroSets.value.firstOrNull { it.id == id }
            ?: File(macroSetsDir, "${sanitizeFileName(id)}.json")
                .takeIf { it.exists() }
                ?.let { f -> runCatching { json.decodeFromString<MacroSet>(f.readText()) }.getOrNull() }
    }

    /**
     * Allows end-users to switch passive sub-macros ON/OFF without exposing or editing macro steps.
     */
    fun togglePassiveEnabled(macroId: String): Macro? {
        val existing = getMacroById(macroId) ?: return null
        if (!existing.isPassive) return null
        val toggled = MacroSecurity.togglePassiveEnabled(existing, !existing.passiveEnabled)
        return persistMacro(toggled)
    }

    fun setPassiveEnabled(macroId: String, enabled: Boolean): Macro? {
        val existing = getMacroById(macroId) ?: return null
        if (!existing.isPassive || MacroSecurity.isTampered(existing)) return null
        val updated = MacroSecurity.togglePassiveEnabled(existing, enabled)
        return persistMacro(updated)
    }

    fun updateMacroRemoteControl(macroId: String, enabled: Boolean, remoteId: String): Macro? {
        val existing = getMacroById(macroId) ?: return null
        if (MacroSecurity.isTampered(existing)) return null
        val cleanId = com.example.data.RemoteIdSanitizer.sanitize(remoteId)
        val updated = MacroSecurity.updateMacroRemoteControl(existing, enabled && cleanId.isNotEmpty(), cleanId)
        return persistMacro(updated)
    }

    fun setRemoteActionActive(macroId: String, actionId: String, active: Boolean): Macro? {
        val existing = getMacroById(macroId) ?: return null
        if (MacroSecurity.isTampered(existing)) return null
        val updated = MacroSecurity.setRemoteActionActive(existing, actionId, active)
        if (updated === existing) return null
        return persistMacro(updated)
    }

    fun deleteMacro(id: String) {
        val existing = getMacroById(id)
        File(macrosDir, "${sanitizeFileName(id)}.json").delete()
        _macros.update { list -> list.filterNot { it.id == id } }
        if (existing != null && existing.logoImage.isNotBlank()) {
            cleanupUnusedIcon(existing.logoImage)
        }
    }

    fun deleteMacroSet(setId: String, deleteMembers: Boolean = true) {
        val existing = getMacroSetById(setId)
        val members = _macros.value.filter { it.setId == setId }
        if (deleteMembers) {
            members.forEach { deleteMacro(it.id) }
        } else {
            members.forEach { m -> persistMacro(m.copy(setId = "")) }
        }
        File(macroSetsDir, "${sanitizeFileName(setId)}.json").delete()
        _macroSets.update { list -> list.filterNot { it.id == setId } }
        if (existing != null && existing.logoImage.isNotBlank()) {
            cleanupUnusedIcon(existing.logoImage)
        }
    }

    fun macroIconPath(iconName: String): String? {
        val clean = sanitizeIconFileName(iconName)
        if (clean.isBlank()) return null
        return File(macroIconsDir, clean).takeIf { it.exists() }?.absolutePath
    }

    private fun cleanupUnusedIcon(iconName: String) {
        val clean = sanitizeIconFileName(iconName)
        if (clean.isBlank()) return
        val usedByMacro = _macros.value.any { sanitizeIconFileName(it.logoImage) == clean }
        val usedBySet = _macroSets.value.any { sanitizeIconFileName(it.logoImage) == clean }
        if (!usedByMacro && !usedBySet) {
            File(macroIconsDir, clean).delete()
        }
    }

    // ---- Private target template storage (hidden from user) ----

    fun loadTemplate(name: String): PreparedTemplate? {
        val file = resolveExistingImageFile(name) ?: return null
        val key = "${file.absolutePath}@${file.lastModified()}"
        templateCache.get(key)?.let { return it }
        val frame = file.decodeFrame() ?: return null
        val prepared = PreparedTemplate(normalizeImageName(name), frame)
        templateCache.put(key, prepared)
        return prepared
    }

    private fun resolveExistingImageFile(rawName: String): File? {
        val norm = normalizeImageName(rawName)
        val direct = File(imagesDir, norm)
        if (direct.exists()) return direct
        val base = norm.substringAfterLast('/')
        val rootFile = File(imagesDir, base)
        if (rootFile.exists()) return rootFile
        return imagesDir.listFiles { f -> f.isDirectory }.orEmpty()
            .firstNotNullOfOrNull { dir -> File(dir, base).takeIf { it.exists() } }
    }

    private fun saveImageBytes(rawName: String, bytes: ByteArray) {
        val normalized = normalizeImageName(rawName)
        val outFile = File(imagesDir, normalized)
        outFile.parentFile?.mkdirs()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        if (bounds.outMimeType == "image/png") {
            outFile.writeBytes(bytes)
        } else {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            try {
                bmp.savePng(outFile)
            } finally {
                bmp.recycle()
            }
        }
        templateCache.evictAll()
    }

    private fun saveMacroLogoBytes(rawFileName: String, bytes: ByteArray) {
        val clean = sanitizeIconFileName(rawFileName)
        if (clean.isBlank()) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        val file = File(macroIconsDir, clean)
        if (bounds.outMimeType == "image/png") {
            file.writeBytes(bytes)
        } else {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            try {
                bmp.savePng(file)
            } finally {
                bmp.recycle()
            }
        }
    }

    // ---- Strictly Encrypted .wjm Import ----

    fun importWjmFromStream(input: InputStream): ImportSummary {
        val decoded = MacroBundleCodec.readEncryptedWjm(input)
        val oneTimeTokens = MacroBundleCodec.validateAndExtractOneTimeTokens(
            decoded = decoded,
            alreadyConsumedTokens = loadConsumedImportTokens()
        )

        decoded.images.forEach { (name, bytes) -> saveImageBytes(name, bytes) }
        decoded.macroIcons.forEach { (iconName, bytes) -> saveMacroLogoBytes(iconName, bytes) }

        val now = effectiveNowMs()
        val existingSets = _macroSets.value
        val existingMacros = _macros.value

        var setsOverwritten = 0
        var macrosOverwritten = 0
        var deadlinesExtended = 0

        val setFinalIdMap = HashMap<String, String>()
        val matchedSets = LinkedHashMap<String, MacroSet>()
        for (incomingSet in decoded.macroSets) {
            val existingSet = findMatchingSet(incomingSet, existingSets)
            if (existingSet != null) {
                matchedSets[incomingSet.id] = existingSet
                setFinalIdMap[incomingSet.id] = existingSet.id
            } else {
                setFinalIdMap[incomingSet.id] = incomingSet.id
            }
        }
        for (incomingSet in decoded.macroSets) {
            val existingSet = matchedSets[incomingSet.id]
            if (existingSet != null) {
                val res = MacroSecurity.mergeOverwrittenMacroSet(existingSet, incomingSet, now)
                persistMacroSet(res.merged)
                setsOverwritten++
                if (res.deadlineExtended) deadlinesExtended++
            } else {
                persistMacroSet(incomingSet)
            }
        }

        val finalIdMap = HashMap<String, String>()
        val matchedExisting = LinkedHashMap<String, Macro>()
        for (incoming in decoded.macros) {
            val existing = findMatchingMacro(incoming, existingMacros)
            if (existing != null) {
                matchedExisting[incoming.id] = existing
                finalIdMap[incoming.id] = existing.id
            } else {
                finalIdMap[incoming.id] = incoming.id
            }
        }

        for (incoming in decoded.macros) {
            val existing = matchedExisting[incoming.id]
            val finalSetId = if (incoming.setId.isNotBlank()) setFinalIdMap[incoming.setId] ?: incoming.setId else ""
            fun relinkList(list: List<Action>) = list.mapAllActions { a ->
                when {
                    a is RunMacroAction && a.targetMacroId in finalIdMap ->
                        a.copy(targetMacroId = finalIdMap[a.targetMacroId]!!)
                    a is FinishMacroAction && a.targetMacroId in finalIdMap ->
                        a.copy(targetMacroId = finalIdMap[a.targetMacroId]!!)
                    else -> a
                }
            }
            val relinked = incoming.copy(
                setId = finalSetId,
                actions = relinkList(incoming.actions),
                branches = incoming.branches.map { b -> b.copy(actions = relinkList(b.actions)) },
                stuckActions = relinkList(incoming.stuckActions),
                accountSwitch = incoming.accountSwitch.copy(
                    switchActions = relinkList(incoming.accountSwitch.switchActions)
                )
            )
            if (existing != null) {
                val res = MacroSecurity.mergeOverwrittenMacro(existing, relinked, now)
                persistMacro(res.merged)
                macrosOverwritten++
                if (res.deadlineExtended) deadlinesExtended++
            } else {
                persistMacro(relinked)
            }
        }

        if (oneTimeTokens.isNotEmpty()) {
            markImportTokensConsumed(oneTimeTokens)
        }

        return ImportSummary(
            macrosImported = decoded.macros.size,
            setsImported = decoded.macroSets.size,
            macrosOverwritten = macrosOverwritten,
            setsOverwritten = setsOverwritten,
            deadlinesExtended = deadlinesExtended,
            singleUseImport = oneTimeTokens.isNotEmpty()
        )
    }

    private fun findMatchingSet(incoming: MacroSet, candidates: List<MacroSet>): MacroSet? {
        val incOrigin = incoming.originId.trim()
        if (incOrigin.isNotBlank()) {
            candidates.firstOrNull { it.originId == incOrigin || it.id == incOrigin }?.let { return it }
        }
        val incName = incoming.name.trim()
        if (incName.isBlank()) return null
        return candidates.firstOrNull { it.name.trim().equals(incName, ignoreCase = true) }
    }

    private fun findMatchingMacro(incoming: Macro, candidates: List<Macro>): Macro? {
        val incOrigin = incoming.originId.trim()
        if (incOrigin.isNotBlank()) {
            candidates.firstOrNull { it.originId == incOrigin || it.id == incOrigin }?.let { return it }
        }
        val incName = incoming.name.trim()
        if (incName.isBlank()) return null
        return candidates.firstOrNull { it.name.trim().equals(incName, ignoreCase = true) }
    }

    private fun loadConsumedImportTokens(): MutableSet<String> = synchronized(consumedTokensLock) {
        val merged = HashSet<String>()
        prefs.getStringSet(KEY_CONSUMED_TOKENS, emptySet())
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
        prefs.edit().putStringSet(KEY_CONSUMED_TOKENS, HashSet(all)).commit()
        runCatching {
            val tmp = File(appContext.filesDir, "consumed_wjm_tokens.tmp")
            tmp.writeText(all.joinToString("\n"), Charsets.UTF_8)
            if (!tmp.renameTo(consumedTokensFile)) {
                consumedTokensFile.writeText(all.joinToString("\n"), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    companion object {
        private const val KEY_MAX_SEEN_EPOCH_MS = "player_max_seen_epoch_ms"
        private const val KEY_CONSUMED_TOKENS = "player_consumed_wjm_tokens_v1"
        private const val MAX_CACHED_TEMPLATES = 32
        private const val WATERMARK_PERSIST_INTERVAL_MS = 30_000L

        @Volatile
        private var instance: PlayerStore? = null

        fun get(context: Context): PlayerStore =
            instance ?: synchronized(this) {
                instance ?: PlayerStore(context.applicationContext).also { instance = it }
            }

        private fun sanitizeFolderName(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9_\\- ]"), "").trim().replace(Regex("\\s+"), "_").take(36)

        private fun normalizeImageName(raw: String): String {
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

        private fun sanitizeIconFileName(raw: String): String {
            val base = sanitizeFileName(
                raw.substringAfterLast('/').substringAfterLast('\\')
                    .removeSuffix(".png").removeSuffix(".PNG")
                    .removeSuffix(".jpg").removeSuffix(".JPG")
                    .removeSuffix(".jpeg").removeSuffix(".JPEG")
                    .removeSuffix(".webp").removeSuffix(".WEBP")
            )
            return if (base.isBlank()) "" else "$base.png"
        }

        private fun sanitizeFileName(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(64)
    }
}
