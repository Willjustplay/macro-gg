package com.wjust.exportbridge

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local ContentProvider backing `Wjust Export Bridge`.
 *
 * When Wjust macro v2.6.0 (or any app) invokes `ACTION_CREATE_DOCUMENT` on CloudPhones where
 * Android's DocumentsUI was removed, this provider hands out a writable staging descriptor.
 * The moment the caller finishes writing and closes the stream, [ParcelFileDescriptor.OnCloseListener]
 * immediately publishes the finished `.zip` / `.png` file into `Download/WjustMacro/`
 * (via MediaStore.Downloads on Android 10+ or direct storage on Android 7-9) and keeps an internal
 * archive copy so the user can inspect or share it from [BridgeMainActivity].
 */
class BridgeExportProvider : ContentProvider() {

    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean {
        val thread = HandlerThread("WjustBridgeIO").apply { start() }
        ioThread = thread
        ioHandler = Handler(thread.looper)
        return true
    }

    override fun getType(uri: Uri): String {
        val name = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
        return when {
            name.endsWith(".zip") -> "application/zip"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val file = resolveReadableFile(ctx, uri)
        val name = file?.name ?: uri.lastPathSegment.orEmpty().ifBlank { "wjust_macro_backup.zip" }
        val size = file?.length() ?: 0L
        val row = Array<Any?>(cols.size) { idx ->
            when (cols[idx]) {
                OpenableColumns.DISPLAY_NAME -> name
                OpenableColumns.SIZE -> size
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Context unavailable")
        val segments = uri.pathSegments
        if (segments.size < 2) throw FileNotFoundException("Invalid bridge URI: $uri")
        val bucket = segments[0]
        val safeName = sanitizeName(segments[1])

        if (bucket == BUCKET_WRITE && mode.contains("w")) {
            val stagingDir = File(ctx.cacheDir, "bridge_staging").apply { mkdirs() }
            val stagingFile = File(stagingDir, safeName)
            if (!stagingFile.exists()) stagingFile.createNewFile()
            val mime = getType(uri)
            val handler = ioHandler ?: Handler(Looper.getMainLooper())

            return ParcelFileDescriptor.open(
                stagingFile,
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
                handler
            ) { closeError ->
                if (closeError == null && stagingFile.exists() && stagingFile.length() > 0L) {
                    val savedPath = publishCompletedExport(ctx, stagingFile, safeName, mime)
                    val kb = ((stagingFile.length() + 1023L) / 1024L).coerceAtLeast(1L)
                    mainHandler.post {
                        Toast.makeText(
                            ctx,
                            "Tersimpan di $savedPath ($kb KB)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                runCatching { stagingFile.delete() }
            }
        }

        val readable = resolveReadableFile(ctx, uri)
            ?: throw FileNotFoundException("File not found: $uri")
        return ParcelFileDescriptor.open(readable, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun resolveReadableFile(ctx: Context, uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val bucket = segments[0]
        val safeName = sanitizeName(segments[1])
        return when (bucket) {
            BUCKET_SAVED -> File(savedCopiesDir(ctx), safeName).takeIf { it.exists() }
            BUCKET_EXT_DL -> {
                val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
                File(File(base, SUBFOLDER), safeName).takeIf { it.exists() }
            }
            BUCKET_PUB_DL -> {
                @Suppress("DEPRECATION")
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(File(dl, SUBFOLDER), safeName).takeIf { it.exists() }
                    ?: File(dl, safeName).takeIf { it.exists() }
            }
            BUCKET_WRITE -> File(File(ctx.cacheDir, "bridge_staging"), safeName).takeIf { it.exists() }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    data class ExportedItem(
        val name: String,
        val displayPath: String,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val readUri: Uri
    )

    companion object {
        const val AUTHORITY = "com.wjust.exportbridge.provider"
        const val SUBFOLDER = "WjustMacro"
        private const val BUCKET_WRITE = "write"
        private const val BUCKET_SAVED = "saved"
        private const val BUCKET_EXT_DL = "ext_dl"
        private const val BUCKET_PUB_DL = "pub_dl"

        fun buildWriteUri(desiredTitle: String?): Pair<String, Uri> {
            val clean = sanitizeName(desiredTitle.orEmpty().ifBlank { "wjust_macro_backup.zip" })
            val dot = clean.lastIndexOf('.')
            val base = if (dot > 0) clean.substring(0, dot) else clean
            val ext = if (dot > 0) clean.substring(dot) else ".zip"
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val finalName = "${base}_$stamp$ext"
            val uri = Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(BUCKET_WRITE)
                .appendPath(finalName)
                .build()
            return finalName to uri
        }

        fun savedCopiesDir(ctx: Context): File =
            File(ctx.filesDir, "exported").apply { mkdirs() }

        fun publishCompletedExport(ctx: Context, source: File, fileName: String, mimeType: String): String {
            // Always save a verified local archive copy inside bridge app so user can verify/share anytime
            runCatching {
                val archiveDir = savedCopiesDir(ctx)
                source.copyTo(File(archiveDir, fileName), overwrite = true)
                // Keep latest 25 archive copies to keep app footprint tidy
                archiveDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(25)
                    ?.forEach { it.delete() }
            }

            // Tier 1: Android 10+ MediaStore.Downloads -> /sdcard/Download/WjustMacro/<fileName>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val msOk = runCatching {
                    val resolver = ctx.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("MediaStore unavailable")
                    try {
                        resolver.openOutputStream(itemUri)?.use { out ->
                            source.inputStream().use { input -> input.copyTo(out) }
                        } ?: error("Cannot open MediaStore stream")
                        resolver.update(
                            itemUri,
                            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                            null,
                            null
                        )
                    } catch (t: Throwable) {
                        runCatching { resolver.delete(itemUri, null, null) }
                        throw t
                    }
                }.isSuccess
                if (msOk) return "Download/$SUBFOLDER/$fileName"
            }

            // Tier 2: Direct public /sdcard/Download/WjustMacro/<fileName>
            val pubOk = runCatching {
                @Suppress("DEPRECATION")
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(dl, SUBFOLDER).apply { mkdirs() }
                FileOutputStream(File(targetDir, fileName)).use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                }
            }.isSuccess
            if (pubOk) return "Download/$SUBFOLDER/$fileName"

            // Tier 3: Always-writable external app downloads folder
            val extBase = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            val targetDir = File(extBase, SUBFOLDER).apply { mkdirs() }
            runCatching {
                FileOutputStream(File(targetDir, fileName)).use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                }
            }
            return "Android/data/${ctx.packageName}/files/Download/$SUBFOLDER/$fileName"
        }

        fun listExportedAndImportableFiles(ctx: Context): List<ExportedItem> {
            val out = ArrayList<ExportedItem>()
            val seen = HashSet<String>()

            fun addUnique(item: ExportedItem) {
                val key = "${item.name.lowercase(Locale.ROOT)}|${item.sizeBytes}"
                if (seen.add(key)) out += item
            }

            // 1. Bridge verified copies (always readable & shareable)
            savedCopiesDir(ctx).listFiles { f -> f.isFile && isSupportedFile(f.name) }
                .orEmpty()
                .forEach { f ->
                    val uri = Uri.Builder()
                        .scheme("content")
                        .authority(AUTHORITY)
                        .appendPath(BUCKET_SAVED)
                        .appendPath(f.name)
                        .build()
                    addUnique(ExportedItem(f.name, "Download/$SUBFOLDER/${f.name}", f.length(), f.lastModified(), uri))
                }

            // 2. Android 10+ MediaStore.Downloads
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val proj = arrayOf(
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_MODIFIED,
                        MediaStore.Downloads.RELATIVE_PATH
                    )
                    ctx.contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        proj,
                        null,
                        null,
                        "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                    )?.use { c ->
                        val idC = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        val nameC = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                        val sizeC = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                        val modC = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                        val pathC = c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                        while (c.moveToNext()) {
                            val name = c.getString(nameC).orEmpty().trim()
                            if (!isSupportedFile(name)) continue
                            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idC))
                            val rel = c.getString(pathC).orEmpty().trimEnd('/').ifBlank { "Download" }
                            addUnique(ExportedItem(name, "$rel/$name", c.getLong(sizeC), c.getLong(modC) * 1000L, uri))
                        }
                    }
                }
            }

            // 3. Public Download/WjustMacro & Download/
            runCatching {
                @Suppress("DEPRECATION")
                val pubDl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                listOf(File(pubDl, SUBFOLDER) to "Download/$SUBFOLDER", pubDl to "Download").forEach { (dir, label) ->
                    dir.listFiles { f -> f.isFile && isSupportedFile(f.name) }
                        .orEmpty()
                        .forEach { f ->
                            val uri = Uri.Builder()
                                .scheme("content")
                                .authority(AUTHORITY)
                                .appendPath(BUCKET_PUB_DL)
                                .appendPath(f.name)
                                .build()
                            addUnique(ExportedItem(f.name, "$label/${f.name}", f.length(), f.lastModified(), uri))
                        }
                }
            }

            // 4. Bridge external Downloads fallback
            ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { ext ->
                File(ext, SUBFOLDER).listFiles { f -> f.isFile && isSupportedFile(f.name) }
                    .orEmpty()
                    .forEach { f ->
                        val uri = Uri.Builder()
                            .scheme("content")
                            .authority(AUTHORITY)
                            .appendPath(BUCKET_EXT_DL)
                            .appendPath(f.name)
                            .build()
                        addUnique(ExportedItem(f.name, "Bridge/Download/$SUBFOLDER/${f.name}", f.length(), f.lastModified(), uri))
                    }
            }

            return out.sortedByDescending { it.modifiedMs }
        }

        private fun isSupportedFile(name: String): Boolean {
            val l = name.lowercase(Locale.ROOT)
            return l.endsWith(".zip") || l.endsWith(".json") || l.endsWith(".png")
        }

        fun sanitizeName(raw: String): String {
            val cleaned = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            val dot = cleaned.lastIndexOf('.')
            val base = (if (dot > 0) cleaned.substring(0, dot) else cleaned)
                .replace(Regex("[^A-Za-z0-9_\\-]+"), "_")
                .trim('_')
                .ifBlank { "wjust_macro_backup" }
                .take(48)
            val ext = if (dot > 0) cleaned.substring(dot).lowercase(Locale.ROOT) else ".zip"
            return "$base$ext"
        }
    }
}
