package com.example.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Thrown when importing a single-use (1× import) bundle that was already used or tampered with. */
class SingleUseImportException(message: String) : IllegalStateException(message)

/** Optional signed manifest stored as `bundle_meta.json` inside exported macro `.zip` files. */
@Serializable
data class ExportBundleMeta(
    val singleUseImport: Boolean = false,
    val importToken: String = "",
    val exportedAtEpochMs: Long = 0L,
    val macroOriginId: String = "",
    val metaSeal: String = ""
)

/**
 * Pure-Kotlin portable ZIP bundle encoder/decoder for MacroGG macros, linked sub-macros,
 * Lua scripts, and target PNG image assets.
 */
object MacroBundleCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    data class ExportResult(
        val macrosExported: Int,
        val imagesExported: Int,
        val singleUseImport: Boolean = false,
        val importToken: String = ""
    )

    data class DecodedBundle(
        val macros: List<Macro>,
        val scripts: List<LuaScript>,
        val images: Map<String, ByteArray>,
        val macroIcons: Map<String, ByteArray> = emptyMap(),
        val bundleMeta: ExportBundleMeta? = null
    )

    /**
     * Collects [root] plus any recursively linked [RunMacroAction] sub-macros (cycle-safe).
     */
    fun collectMacroBundle(root: Macro, lookupMacro: (String) -> Macro?): List<Macro> {
        val result = ArrayList<Macro>()
        val visited = HashSet<String>()
        fun visit(m: Macro) {
            if (!visited.add(m.id)) return
            result += m
            for (action in m.allActionsSequence()) {
                if (action is RunMacroAction && action.targetMacroId.isNotBlank()) {
                    lookupMacro(action.targetMacroId)?.let { visit(it) }
                }
            }
        }
        visit(root)
        return result
    }

    /**
     * Writes a portable .zip containing:
     * - `macro.json` (root macro)
     * - `bundle_meta.json` (when [singleUseImport] is enabled, carrying signed 1× import token)
     * - `linked_macros/<id>.json` (any linked sub-macros)
     * - `images/<filename>.png` (every referenced target image across root, nested branches, and linked macros)
     * - `macro_icons/<filename>.png` (any custom macro logo photos)
     */
    fun writeMacroZip(
        root: Macro,
        lookupMacro: (String) -> Macro?,
        loadImage: (imageName: String) -> Pair<String, ByteArray>?,
        output: OutputStream,
        loadMacroIcon: (iconName: String) -> Pair<String, ByteArray>? = { null },
        singleUseImport: Boolean = false,
        exportedAtEpochMs: Long = System.currentTimeMillis()
    ): ExportResult {
        val stableOrigin = root.originId.ifBlank { root.id }
        val baseRoot = root.copy(originId = stableOrigin)
        val generatedToken = if (singleUseImport) MacroSecurity.generateSalt() else ""
        val exportRoot = if (singleUseImport) {
            MacroSecurity.attachOneTimeExportToken(baseRoot, generatedToken)
        } else {
            baseRoot
        }

        val bundle = collectMacroBundle(exportRoot, lookupMacro)
        val referencedImages = LinkedHashSet<String>()
        val referencedIcons = LinkedHashSet<String>()
        bundle.forEach { m ->
            referencedImages += m.imageNames()
            if (m.logoImage.isNotBlank()) referencedIcons += m.logoImage
        }

        var imagesWritten = 0
        ZipOutputStream(output).use { zip ->
            if (singleUseImport) {
                val meta = ExportBundleMeta(
                    singleUseImport = true,
                    importToken = generatedToken,
                    exportedAtEpochMs = exportedAtEpochMs,
                    macroOriginId = stableOrigin,
                    metaSeal = MacroSecurity.computeOneTimeBundleSeal(generatedToken, exportedAtEpochMs, stableOrigin)
                )
                zip.putNextEntry(ZipEntry("bundle_meta.json"))
                zip.write(json.encodeToString(meta).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("macro.json"))
            zip.write(json.encodeToString(exportRoot).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (linked in bundle.drop(1)) {
                zip.putNextEntry(ZipEntry("linked_macros/${sanitizeFileName(linked.id)}.json"))
                zip.write(json.encodeToString(linked).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            val writtenNames = HashSet<String>()
            for (imgName in referencedImages) {
                val resolved = loadImage(imgName) ?: continue
                val (fileName, bytes) = resolved
                if (fileName.isNotBlank() && bytes.isNotEmpty() && writtenNames.add(fileName)) {
                    zip.putNextEntry(ZipEntry("images/$fileName"))
                    zip.write(bytes)
                    zip.closeEntry()
                    imagesWritten++
                }
            }

            val writtenIcons = HashSet<String>()
            for (iconName in referencedIcons) {
                val resolved = loadMacroIcon(iconName) ?: continue
                val (fileName, bytes) = resolved
                if (fileName.isNotBlank() && bytes.isNotEmpty() && writtenIcons.add(fileName)) {
                    zip.putNextEntry(ZipEntry("macro_icons/$fileName"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return ExportResult(
            macrosExported = bundle.size,
            imagesExported = imagesWritten,
            singleUseImport = singleUseImport,
            importToken = generatedToken
        )
    }

    /**
     * Reads either a raw single-macro JSON stream or a .zip bundle (macro export or full backup),
     * remapping macro IDs so [RunMacroAction] links remain intact after import.
     */
    fun readBundle(input: InputStream, idGenerator: () -> String = { newId() }): DecodedBundle {
        val buffered = BufferedInputStream(input)
        buffered.mark(4)
        val header = ByteArray(4)
        val read = buffered.read(header)
        buffered.reset()

        val isZip = read >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        if (!isZip) {
            val text = buffered.readBytes().toString(Charsets.UTF_8)
            val macro = json.decodeFromString<Macro>(text)
            val cloned = macro.copy(
                id = idGenerator(),
                originId = macro.originId.ifBlank { macro.id },
                actions = macro.actions.map { it.withNewIds() },
                stuckActions = macro.stuckActions.map { it.withNewIds() }
            )
            return DecodedBundle(macros = listOf(cloned), scripts = emptyList(), images = emptyMap())
        }

        val rawMacros = ArrayList<Macro>()
        val scripts = ArrayList<LuaScript>()
        val images = LinkedHashMap<String, ByteArray>()
        val macroIcons = LinkedHashMap<String, ByteArray>()
        var bundleMeta: ExportBundleMeta? = null

        ZipInputStream(buffered).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val bytes = readZipEntryBytes(zis)
                val name = entry.name
                when {
                    name == "bundle_meta.json" -> {
                        bundleMeta = runCatching {
                            json.decodeFromString<ExportBundleMeta>(bytes.toString(Charsets.UTF_8))
                        }.getOrNull() ?: ExportBundleMeta(singleUseImport = true, importToken = "", metaSeal = "INVALID")
                    }
                    name == "macro.json" ||
                        (name.startsWith("macros/") && name.endsWith(".json")) ||
                        (name.startsWith("linked_macros/") && name.endsWith(".json")) -> {
                        runCatching { json.decodeFromString<Macro>(bytes.toString(Charsets.UTF_8)) }
                            .getOrNull()?.let { rawMacros += it }
                    }
                    name.startsWith("scripts/") && name.endsWith(".json") -> {
                        runCatching { json.decodeFromString<LuaScript>(bytes.toString(Charsets.UTF_8)) }
                            .getOrNull()?.let { scripts += it.copy(id = idGenerator()) }
                    }
                    name.startsWith("macro_icons/") && (
                        name.endsWith(".png", ignoreCase = true) ||
                            name.endsWith(".jpg", ignoreCase = true) ||
                            name.endsWith(".jpeg", ignoreCase = true) ||
                            name.endsWith(".webp", ignoreCase = true)
                        ) -> {
                        val iconName = name.substringAfterLast('/').trim()
                        if (iconName.isNotBlank() && bytes.isNotEmpty()) {
                            macroIcons[iconName] = bytes
                        }
                    }
                    name.endsWith(".png", ignoreCase = true) ||
                        name.endsWith(".jpg", ignoreCase = true) ||
                        name.endsWith(".jpeg", ignoreCase = true) ||
                        name.endsWith(".webp", ignoreCase = true) -> {
                        val relName = if (name.startsWith("images/")) {
                            name.removePrefix("images/")
                        } else {
                            name.substringAfterLast('/')
                        }.trimStart('/')
                        if (relName.isNotBlank() && bytes.isNotEmpty()) {
                            images[relName] = bytes
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        // Remap old macro IDs -> fresh IDs while preserving originId so same-macro overwrite & deadline stacking work
        val idRemap = HashMap<String, String>()
        rawMacros.forEach { m -> idRemap[m.id] = idGenerator() }
        val remappedMacros = rawMacros.map { m ->
            fun remapList(list: List<Action>) = list.map { it.withNewIds() }.mapAllActions { a ->
                if (a is RunMacroAction && a.targetMacroId in idRemap) {
                    a.copy(targetMacroId = idRemap[a.targetMacroId]!!)
                } else a
            }
            m.copy(
                id = idRemap[m.id] ?: idGenerator(),
                originId = m.originId.ifBlank { m.id },
                actions = remapList(m.actions),
                stuckActions = remapList(m.stuckActions)
            )
        }

        return DecodedBundle(
            macros = remappedMacros,
            scripts = scripts,
            images = images,
            macroIcons = macroIcons,
            bundleMeta = bundleMeta
        )
    }

    /**
     * Validates all one-time import tokens inside [decoded] against manual tampering and against
     * [alreadyConsumedTokens]. Returns the set of new tokens to mark as consumed upon commit, or
     * throws [SingleUseImportException] if tampered or already imported before.
     */
    fun validateAndExtractOneTimeTokens(
        decoded: DecodedBundle,
        alreadyConsumedTokens: Set<String>
    ): Set<String> {
        val tokens = LinkedHashSet<String>()
        val meta = decoded.bundleMeta
        if (meta != null && meta.singleUseImport) {
            if (!MacroSecurity.verifyOneTimeBundleMeta(
                    token = meta.importToken,
                    exportedAtEpochMs = meta.exportedAtEpochMs,
                    originId = meta.macroOriginId,
                    metaSeal = meta.metaSeal
                )
            ) {
                throw SingleUseImportException("File ekspor 1× impor tidak valid atau telah dimodifikasi.")
            }
            tokens += meta.importToken.trim()
        }

        for (m in decoded.macros) {
            val tok = m.oneTimeImportToken.trim()
            val seal = m.oneTimeImportSeal.trim()
            if (tok.isNotBlank() || seal.isNotBlank()) {
                if (!MacroSecurity.verifyMacroOneTimeToken(m)) {
                    throw SingleUseImportException("Token sekali-impor pada macro '${m.name}' tidak valid atau telah diubah.")
                }
                tokens += tok
            }
            // If bundle_meta declares single-use, ensure the root macro matches if it carries a token
            if (meta != null && meta.singleUseImport && tok.isNotBlank() && tok != meta.importToken.trim()) {
                throw SingleUseImportException("Token bundle 1× impor tidak cocok dengan isi macro '${m.name}'.")
            }
        }

        if (tokens.any { it in alreadyConsumedTokens }) {
            throw SingleUseImportException(
                "File macro ini hanya dapat diimpor 1 kali dan sudah pernah digunakan di perangkat ini."
            )
        }
        return tokens
    }

    private fun readZipEntryBytes(zis: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = zis.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun sanitizeFileName(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(64)
}
