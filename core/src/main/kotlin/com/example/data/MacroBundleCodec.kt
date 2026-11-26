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

/** Optional signed manifest stored as `bundle_meta.json` inside exported macro / macro-set `.zip` files. */
@Serializable
data class ExportBundleMeta(
    val singleUseImport: Boolean = false,
    val importToken: String = "",
    val exportedAtEpochMs: Long = 0L,
    val macroOriginId: String = "",
    val metaSeal: String = "",
    val bundleKind: String = "MACRO"
)

/**
 * Pure-Kotlin portable ZIP bundle encoder/decoder for MacroGG macros, macro sets, linked sub-macros,
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
        val importToken: String = "",
        val setsExported: Int = 0
    )

    data class DecodedBundle(
        val macros: List<Macro>,
        val scripts: List<LuaScript>,
        val images: Map<String, ByteArray>,
        val macroIcons: Map<String, ByteArray> = emptyMap(),
        val bundleMeta: ExportBundleMeta? = null,
        val macroSets: List<MacroSet> = emptyList()
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
                } else if (action is FinishMacroAction &&
                    action.mode == FinishMacroMode.SWITCH_TO_SPECIFIC_ACTIVE &&
                    action.targetMacroId.isNotBlank()
                ) {
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
     * Writes a portable Macro Set `.zip` containing:
     * - `macro_set.json` (set metadata, lock, expiry, seal)
     * - `bundle_meta.json` (when [singleUseImport] is enabled, carrying signed 1× import token)
     * - `macros/<id>.json` (all member macros belonging to [set])
     * - `linked_macros/<id>.json` (any external linked sub-macros referenced by members)
     * - `images/<filename>.png` (every referenced target image across all bundled macros)
     * - `macro_icons/<filename>.png` (custom set logo and member macro logos)
     */
    fun writeMacroSetZip(
        set: MacroSet,
        memberMacros: List<Macro>,
        lookupMacro: (String) -> Macro?,
        loadImage: (imageName: String) -> Pair<String, ByteArray>?,
        output: OutputStream,
        loadMacroIcon: (iconName: String) -> Pair<String, ByteArray>? = { null },
        singleUseImport: Boolean = false,
        exportedAtEpochMs: Long = System.currentTimeMillis()
    ): ExportResult {
        val stableSetOrigin = set.originId.ifBlank { set.id }
        val baseSet = set.copy(originId = stableSetOrigin)
        val generatedToken = if (singleUseImport) MacroSecurity.generateSalt() else ""
        val exportSet = if (singleUseImport) {
            MacroSecurity.attachOneTimeExportToken(baseSet, generatedToken)
        } else {
            baseSet
        }

        val memberIds = memberMacros.map { it.id }.toHashSet()
        val exportMembers = memberMacros.map { rawMember ->
            val withOriginAndSet = rawMember.copy(
                originId = rawMember.originId.ifBlank { rawMember.id },
                setId = exportSet.id
            )
            if (singleUseImport) {
                MacroSecurity.attachOneTimeExportToken(withOriginAndSet, generatedToken)
            } else {
                withOriginAndSet
            }
        }

        // Collect any recursively linked sub-macros outside the set so RunMacro/FinishMacro links never break
        val visited = HashSet<String>(memberIds)
        val externalLinked = ArrayList<Macro>()
        for (member in exportMembers) {
            for (linked in collectMacroBundle(member, lookupMacro).drop(1)) {
                if (visited.add(linked.id)) {
                    val prepared = linked.copy(
                        originId = linked.originId.ifBlank { linked.id },
                        setId = ""
                    )
                    externalLinked += if (singleUseImport) {
                        MacroSecurity.attachOneTimeExportToken(prepared, generatedToken)
                    } else {
                        prepared
                    }
                }
            }
        }

        val allMacros = exportMembers + externalLinked
        val referencedImages = LinkedHashSet<String>()
        val referencedIcons = LinkedHashSet<String>()
        if (exportSet.logoImage.isNotBlank()) referencedIcons += exportSet.logoImage
        allMacros.forEach { m ->
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
                    macroOriginId = stableSetOrigin,
                    metaSeal = MacroSecurity.computeOneTimeBundleSeal(generatedToken, exportedAtEpochMs, stableSetOrigin),
                    bundleKind = "MACRO_SET"
                )
                zip.putNextEntry(ZipEntry("bundle_meta.json"))
                zip.write(json.encodeToString(meta).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("macro_set.json"))
            zip.write(json.encodeToString(exportSet).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (member in exportMembers) {
                zip.putNextEntry(ZipEntry("macros/${sanitizeFileName(member.id)}.json"))
                zip.write(json.encodeToString(member).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            for (linked in externalLinked) {
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
            macrosExported = allMacros.size,
            imagesExported = imagesWritten,
            singleUseImport = singleUseImport,
            importToken = generatedToken,
            setsExported = 1
        )
    }

    /**
     * Writes an AES-256-GCM encrypted `.wjm` single-macro bundle (including linked sub-macros,
     * custom logos, and target PNGs) playable exclusively inside Wjust Player.
     */
    fun writeEncryptedMacroWjm(
        root: Macro,
        lookupMacro: (String) -> Macro?,
        loadImage: (imageName: String) -> Pair<String, ByteArray>?,
        output: OutputStream,
        loadMacroIcon: (iconName: String) -> Pair<String, ByteArray>? = { null },
        singleUseImport: Boolean = false,
        exportedAtEpochMs: Long = System.currentTimeMillis()
    ): ExportResult {
        val zipBuffer = ByteArrayOutputStream()
        val result = writeMacroZip(
            root = root,
            lookupMacro = lookupMacro,
            loadImage = loadImage,
            output = zipBuffer,
            loadMacroIcon = loadMacroIcon,
            singleUseImport = singleUseImport,
            exportedAtEpochMs = exportedAtEpochMs
        )
        WjmCrypto.encryptZipToStream(zipBuffer.toByteArray(), output)
        return result
    }

    /**
     * Writes an AES-256-GCM encrypted `.wjm` Macro Set bundle (set metadata, all members,
     * linked sub-macros, logos, and target PNGs) playable exclusively inside Wjust Player.
     */
    fun writeEncryptedMacroSetWjm(
        set: MacroSet,
        memberMacros: List<Macro>,
        lookupMacro: (String) -> Macro?,
        loadImage: (imageName: String) -> Pair<String, ByteArray>?,
        output: OutputStream,
        loadMacroIcon: (iconName: String) -> Pair<String, ByteArray>? = { null },
        singleUseImport: Boolean = false,
        exportedAtEpochMs: Long = System.currentTimeMillis()
    ): ExportResult {
        val zipBuffer = ByteArrayOutputStream()
        val result = writeMacroSetZip(
            set = set,
            memberMacros = memberMacros,
            lookupMacro = lookupMacro,
            loadImage = loadImage,
            output = zipBuffer,
            loadMacroIcon = loadMacroIcon,
            singleUseImport = singleUseImport,
            exportedAtEpochMs = exportedAtEpochMs
        )
        WjmCrypto.encryptZipToStream(zipBuffer.toByteArray(), output)
        return result
    }

    /**
     * Strictly reads an AES-256-GCM encrypted `.wjm` bundle for Wjust Player.
     * Rejects unencrypted `.zip`, plain `.json`, or tampered `.wjm` files with [WjmFormatException].
     */
    fun readEncryptedWjm(input: InputStream, idGenerator: () -> String = { newId() }): DecodedBundle {
        val rawBytes = input.readBytes()
        if (!WjmCrypto.isWjmHeader(rawBytes)) {
            throw WjmFormatException("Hanya mendukung file terenkripsi .wjm yang diekspor dari Wjust macro.")
        }
        val zipBytes = WjmCrypto.decryptZipBytes(rawBytes)
        if (zipBytes.size < 2 || zipBytes[0] != 0x50.toByte() || zipBytes[1] != 0x4B.toByte()) {
            throw WjmFormatException("Isi file .wjm tidak valid.")
        }
        return readBundle(zipBytes.inputStream(), idGenerator)
    }

    /**
     * Reads either a raw single-macro JSON stream or a .zip bundle (macro export, macro set export,
     * or full backup), remapping macro & set IDs so [RunMacroAction] and [Macro.setId] links stay intact.
     */
    fun readBundle(input: InputStream, idGenerator: () -> String = { newId() }): DecodedBundle {
        val buffered = BufferedInputStream(input)
        buffered.mark(4)
        val header = ByteArray(4)
        val read = buffered.read(header)
        buffered.reset()

        if (WjmCrypto.isWjmHeader(header, read)) {
            throw WjmFormatException(
                "File .wjm terenkripsi hanya dapat dijalankan di aplikasi Wjust Player (tidak dapat diedit di Wjust macro)."
            )
        }

        val isZip = read >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        if (!isZip) {
            val text = buffered.readBytes().toString(Charsets.UTF_8)
            val macro = json.decodeFromString<Macro>(text)
            val cloned = macro.copy(
                id = idGenerator(),
                originId = macro.originId.ifBlank { macro.id },
                setId = "",
                actions = macro.actions.cloneWithRemappedIds(),
                stuckActions = macro.stuckActions.cloneWithRemappedIds()
            )
            return DecodedBundle(macros = listOf(cloned), scripts = emptyList(), images = emptyMap())
        }

        val rawMacros = ArrayList<Macro>()
        val rawSets = ArrayList<MacroSet>()
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
                    name == "macro_set.json" ||
                        (name.startsWith("macro_sets/") && name.endsWith(".json")) -> {
                        runCatching { json.decodeFromString<MacroSet>(bytes.toString(Charsets.UTF_8)) }
                            .getOrNull()?.let { rawSets += it }
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

        // Remap old MacroSet IDs -> fresh IDs while preserving originId
        val setIdRemap = HashMap<String, String>()
        rawSets.forEach { s -> setIdRemap[s.id] = idGenerator() }
        val remappedSets = rawSets.map { s ->
            s.copy(
                id = setIdRemap[s.id] ?: idGenerator(),
                originId = s.originId.ifBlank { s.id }
            )
        }

        // Remap old macro IDs -> fresh IDs while preserving originId and set membership
        val idRemap = HashMap<String, String>()
        rawMacros.forEach { m -> idRemap[m.id] = idGenerator() }
        val remappedMacros = rawMacros.map { m ->
            fun remapList(list: List<Action>) = list.cloneWithRemappedIds().mapAllActions { a ->
                when {
                    a is RunMacroAction && a.targetMacroId in idRemap ->
                        a.copy(targetMacroId = idRemap[a.targetMacroId]!!)
                    a is FinishMacroAction && a.targetMacroId in idRemap ->
                        a.copy(targetMacroId = idRemap[a.targetMacroId]!!)
                    else -> a
                }
            }
            m.copy(
                id = idRemap[m.id] ?: idGenerator(),
                originId = m.originId.ifBlank { m.id },
                setId = if (m.setId.isNotBlank()) setIdRemap[m.setId].orEmpty() else "",
                actions = remapList(m.actions),
                stuckActions = remapList(m.stuckActions)
            )
        }

        return DecodedBundle(
            macros = remappedMacros,
            scripts = scripts,
            images = images,
            macroIcons = macroIcons,
            bundleMeta = bundleMeta,
            macroSets = remappedSets
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

        for (s in decoded.macroSets) {
            val tok = s.oneTimeImportToken.trim()
            val seal = s.oneTimeImportSeal.trim()
            if (tok.isNotBlank() || seal.isNotBlank()) {
                if (!MacroSecurity.verifySetOneTimeToken(s)) {
                    throw SingleUseImportException("Token sekali-impor pada Macro Set '${s.name}' tidak valid atau telah diubah.")
                }
                tokens += tok
            }
            if (meta != null && meta.singleUseImport && tok.isNotBlank() && tok != meta.importToken.trim()) {
                throw SingleUseImportException("Token bundle 1× impor tidak cocok dengan isi Macro Set '${s.name}'.")
            }
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
