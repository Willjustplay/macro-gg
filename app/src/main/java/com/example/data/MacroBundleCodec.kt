package com.example.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
        val imagesExported: Int
    )

    data class DecodedBundle(
        val macros: List<Macro>,
        val scripts: List<LuaScript>,
        val images: Map<String, ByteArray>
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
            for (action in m.actions.walk()) {
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
     * - `linked_macros/<id>.json` (any linked sub-macros)
     * - `images/<filename>.png` (every referenced image across root, nested branches, and linked macros)
     */
    fun writeMacroZip(
        root: Macro,
        lookupMacro: (String) -> Macro?,
        loadImage: (imageName: String) -> Pair<String, ByteArray>?,
        output: OutputStream
    ): ExportResult {
        val bundle = collectMacroBundle(root, lookupMacro)
        val referencedImages = LinkedHashSet<String>()
        bundle.forEach { m -> referencedImages += m.imageNames() }

        var imagesWritten = 0
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("macro.json"))
            zip.write(json.encodeToString(root).toByteArray(Charsets.UTF_8))
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
        }
        return ExportResult(macrosExported = bundle.size, imagesExported = imagesWritten)
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
            val cloned = macro.copy(id = idGenerator(), actions = macro.actions.map { it.withNewIds() })
            return DecodedBundle(macros = listOf(cloned), scripts = emptyList(), images = emptyMap())
        }

        val rawMacros = ArrayList<Macro>()
        val scripts = ArrayList<LuaScript>()
        val images = LinkedHashMap<String, ByteArray>()

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
                    name.endsWith(".png", ignoreCase = true) ||
                        name.endsWith(".jpg", ignoreCase = true) ||
                        name.endsWith(".jpeg", ignoreCase = true) ||
                        name.endsWith(".webp", ignoreCase = true) -> {
                        val fileName = name.substringAfterLast('/')
                        if (fileName.isNotBlank() && bytes.isNotEmpty()) {
                            images[fileName] = bytes
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        // Remap old macro IDs -> fresh IDs so RunMacroAction references stay connected
        val idRemap = HashMap<String, String>()
        rawMacros.forEach { m -> idRemap[m.id] = idGenerator() }
        val remappedMacros = rawMacros.map { m ->
            val mappedActions = m.actions.map { it.withNewIds() }.mapAllActions { a ->
                if (a is RunMacroAction && a.targetMacroId in idRemap) {
                    a.copy(targetMacroId = idRemap[a.targetMacroId]!!)
                } else a
            }
            m.copy(id = idRemap[m.id] ?: idGenerator(), actions = mappedActions)
        }

        return DecodedBundle(macros = remappedMacros, scripts = scripts, images = images)
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
