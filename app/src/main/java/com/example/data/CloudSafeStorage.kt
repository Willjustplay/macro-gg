package com.example.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CloudPhone-safe (Redfinger, LDCloud, UGPhone, VMOS, AOSP) export & import helper.
 *
 * Many CloudPhone ROMs strip or disable Android's DocumentsUI (`ACTION_CREATE_DOCUMENT` /
 * `ACTION_OPEN_DOCUMENT`), which causes `ActivityNotFoundException` crashes on export/import.
 * This helper provides a reliable multi-tier fallback directly to `Download/WjustMacro/`
 * without requiring extra runtime permissions on Android 10+.
 */
object CloudSafeStorage {

    const val SUBFOLDER = "WjustMacro"

    data class SavedExport(
        val fileName: String,
        val displayPath: String,
        val uri: Uri? = null,
        val file: File? = null
    )

    data class LocalBackupEntry(
        val displayName: String,
        val displayPath: String,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val uri: Uri? = null,
        val file: File? = null
    )

    /**
     * Writes an export directly to `Download/WjustMacro/` using:
     * 1. `MediaStore.Downloads` on Android 10+ (API 29+) — works on Redfinger with zero permissions.
     * 2. Public `/sdcard/Download/WjustMacro/` if writable (Android 7–9 or permissive CloudPhones).
     * 3. App-scoped external `Android/data/<pkg>/files/Download/WjustMacro/` (always writable).
     */
    fun <T> writeToDownloads(
        context: Context,
        desiredFileName: String,
        mimeType: String,
        writer: (OutputStream) -> T
    ): Pair<SavedExport, T> {
        val safeName = ensureSafeExportName(desiredFileName)
        val stampedName = addTimestampIfNeeded(safeName)

        // Tier 1: Android 10+ MediaStore.Downloads -> /sdcard/Download/WjustMacro/<name>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaResult = runCatching {
                val resolver = context.contentResolver
                val relPath = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, stampedName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore.Downloads tidak tersedia")
                try {
                    val out = resolver.openOutputStream(itemUri)
                        ?: error("Tidak dapat membuka output MediaStore")
                    val payload = out.use { writer(it) }
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, doneValues, null, null)
                    SavedExport(
                        fileName = stampedName,
                        displayPath = "Download/$SUBFOLDER/$stampedName",
                        uri = itemUri
                    ) to payload
                } catch (t: Throwable) {
                    runCatching { resolver.delete(itemUri, null, null) }
                    throw t
                }
            }
            if (mediaResult.isSuccess) return mediaResult.getOrThrow()
        }

        // Tier 2: Direct public /sdcard/Download/WjustMacro/ (Android 7-9 or permissive CloudPhone)
        val publicResult = runCatching {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, SUBFOLDER).apply { mkdirs() }
            val outFile = uniqueFile(targetDir, safeName)
            val payload = FileOutputStream(outFile).use { writer(it) }
            SavedExport(
                fileName = outFile.name,
                displayPath = "Download/$SUBFOLDER/${outFile.name}",
                file = outFile
            ) to payload
        }
        if (publicResult.isSuccess) return publicResult.getOrThrow()

        // Tier 3: Always-writable app external Downloads folder
        val extBase = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val fallbackDir = File(extBase, SUBFOLDER).apply { mkdirs() }
        val outFile = uniqueFile(fallbackDir, safeName)
        val payload = FileOutputStream(outFile).use { writer(it) }
        val friendlyPath = if (extBase == context.filesDir) {
            "files/$SUBFOLDER/${outFile.name}"
        } else {
            "Android/data/${context.packageName}/files/Download/$SUBFOLDER/${outFile.name}"
        }
        return SavedExport(
            fileName = outFile.name,
            displayPath = friendlyPath,
            file = outFile
        ) to payload
    }

    /**
     * Scans `Download/WjustMacro/`, `Download/`, and the app external Downloads folder for
     * importable `.zip` and `.json` macro/backup files — lets Redfinger users import even
     * when Android's system file picker (`DocumentsUI`) is removed from the ROM.
     */
    fun listLocalImportFiles(context: Context): List<LocalBackupEntry> {
        val entries = ArrayList<LocalBackupEntry>()
        val seenKeys = HashSet<String>()

        fun addIfUnique(entry: LocalBackupEntry) {
            val key = "${entry.displayName.lowercase(Locale.ROOT)}|${entry.sizeBytes}"
            if (seenKeys.add(key)) {
                entries += entry
            }
        }

        // 1. Query MediaStore.Downloads on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_MODIFIED,
                    MediaStore.Downloads.RELATIVE_PATH
                )
                val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    val modCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)?.trim().orEmpty()
                        if (!isImportableMacroFile(name)) continue
                        val id = cursor.getLong(idCol)
                        val size = cursor.getLong(sizeCol)
                        val modMs = cursor.getLong(modCol) * 1000L
                        val rel = cursor.getString(pathCol)?.trimEnd('/').orEmpty().ifBlank { "Download" }
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        addIfUnique(
                            LocalBackupEntry(
                                displayName = name,
                                displayPath = "$rel/$name",
                                sizeBytes = size,
                                modifiedMs = modMs,
                                uri = uri
                            )
                        )
                    }
                }
            }
        }

        // 2. Scan public Download/WjustMacro, Download/, and app external Downloads folder
        val candidateDirs = buildList {
            runCatching {
                @Suppress("DEPRECATION")
                val pubDl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                add(File(pubDl, SUBFOLDER) to "Download/$SUBFOLDER")
                add(pubDl to "Download")
            }
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { extDl ->
                add(File(extDl, SUBFOLDER) to "App/Download/$SUBFOLDER")
            }
        }
        for ((dir, label) in candidateDirs) {
            runCatching {
                dir.listFiles { f -> f.isFile && isImportableMacroFile(f.name) }
                    .orEmpty()
                    .forEach { file ->
                        addIfUnique(
                            LocalBackupEntry(
                                displayName = file.name,
                                displayPath = "$label/${file.name}",
                                sizeBytes = file.length(),
                                modifiedMs = file.lastModified(),
                                file = file
                            )
                        )
                    }
            }
        }

        return entries.sortedByDescending { it.modifiedMs }
    }

    fun openImportStream(context: Context, entry: LocalBackupEntry): InputStream? {
        entry.uri?.let { u ->
            runCatching { context.contentResolver.openInputStream(u) }.getOrNull()?.let { return it }
        }
        entry.file?.takeIf { it.exists() && it.canRead() }?.let { f ->
            return f.inputStream()
        }
        return null
    }

    internal fun isImportableMacroFile(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".zip") || lower.endsWith(".json")
    }

    internal fun ensureSafeExportName(raw: String): String {
        val trimmed = raw.trim().ifBlank { "wjust_macro.zip" }
        val dot = trimmed.lastIndexOf('.')
        val base = (if (dot > 0) trimmed.substring(0, dot) else trimmed)
            .replace(Regex("[^A-Za-z0-9_\\-]+"), "_")
            .trim('_')
            .ifBlank { "wjust_macro" }
            .take(48)
        val ext = if (dot > 0) trimmed.substring(dot).lowercase(Locale.ROOT) else ".zip"
        return "$base$ext"
    }

    private fun addTimestampIfNeeded(safeName: String): String {
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${base}_$stamp$ext"
    }

    private fun uniqueFile(dir: File, safeName: String): File {
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var candidate = File(dir, "$base$ext")
        var idx = 2
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$idx$ext")
            idx++
        }
        return candidate
    }
}
