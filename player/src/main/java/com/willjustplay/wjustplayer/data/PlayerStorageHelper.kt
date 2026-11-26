package com.willjustplay.wjustplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * CloudPhone & Android-safe scanner that finds encrypted `.wjm` macro files in
 * `Download/WjustMacro/`, `Download/`, and app downloads when system file pickers are unavailable.
 */
object PlayerStorageHelper {

    private const val SUBFOLDER = "WjustMacro"

    data class LocalWjmEntry(
        val displayName: String,
        val displayPath: String,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val uri: Uri? = null,
        val file: File? = null
    )

    fun listLocalWjmFiles(context: Context): List<LocalWjmEntry> {
        val entries = ArrayList<LocalWjmEntry>()
        val seenKeys = HashSet<String>()

        fun addIfUnique(entry: LocalWjmEntry) {
            val key = "${entry.displayName.lowercase(Locale.ROOT)}|${entry.sizeBytes}"
            if (seenKeys.add(key)) {
                entries += entry
            }
        }

        // 1. MediaStore.Downloads on Android 10+
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
                        if (!isWjmFileName(name)) continue
                        val id = cursor.getLong(idCol)
                        val size = cursor.getLong(sizeCol)
                        val modMs = cursor.getLong(modCol) * 1000L
                        val rel = cursor.getString(pathCol)?.trimEnd('/').orEmpty().ifBlank { "Download" }
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        addIfUnique(
                            LocalWjmEntry(
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

        // 2. Public Download/WjustMacro, Download/, and app external Downloads
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
                dir.listFiles { f -> f.isFile && isWjmFileName(f.name) }
                    .orEmpty()
                    .forEach { file ->
                        addIfUnique(
                            LocalWjmEntry(
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

    fun openStream(context: Context, entry: LocalWjmEntry): InputStream? {
        entry.uri?.let { u ->
            runCatching { context.contentResolver.openInputStream(u) }.getOrNull()?.let { return it }
        }
        entry.file?.takeIf { it.exists() && it.canRead() }?.let { f ->
            return f.inputStream()
        }
        return null
    }

    internal fun isWjmFileName(name: String): Boolean =
        name.trim().lowercase(Locale.ROOT).endsWith(".wjm")
}
