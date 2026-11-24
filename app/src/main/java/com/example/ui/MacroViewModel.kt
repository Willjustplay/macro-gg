package com.example.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.automation.AutomationCoordinator
import com.example.automation.LuaSyntaxResult
import com.example.automation.MacroAccessibilityService
import com.example.automation.SandboxedLuaEngine
import com.example.automation.ScreenCaptureService
import com.example.data.Action
import com.example.data.AppSettings
import com.example.data.ClickAction
import com.example.data.ImageAsset
import com.example.data.LocalStore
import com.example.data.LuaScript
import com.example.data.Macro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionHealth(
    val accessibilityEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val screenCaptureActive: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false
)

class MacroViewModel(private val appContext: Context) : ViewModel() {

    private val store = LocalStore.get(appContext)

    val macros: StateFlow<List<Macro>> = store.macros
    val scripts: StateFlow<List<LuaScript>> = store.scripts
    val images: StateFlow<List<ImageAsset>> = store.images
    val imageFolders: StateFlow<List<String>> = store.imageFolders
    val settings: StateFlow<AppSettings> = store.settings

    private val _permissions = MutableStateFlow(PermissionHealth())
    val permissions: StateFlow<PermissionHealth> = _permissions.asStateFlow()

    private val _syntaxResult = MutableStateFlow<LuaSyntaxResult?>(null)
    val syntaxResult: StateFlow<LuaSyntaxResult?> = _syntaxResult.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        AutomationCoordinator.attachStore(appContext)
        refreshPermissions(appContext)
    }

    fun refreshPermissions(context: Context = appContext) {
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        _permissions.value = PermissionHealth(
            accessibilityEnabled = MacroAccessibilityService.isServiceEnabled(context) ||
                MacroAccessibilityService.isConnected.value,
            overlayEnabled = Settings.canDrawOverlays(context),
            screenCaptureActive = ScreenCaptureService.isCapturing.value,
            batteryOptimizationIgnored = ignoringBattery
        )
    }

    // ---- Macros ----

    fun saveMacro(macro: Macro): Macro {
        val saved = store.saveMacro(macro)
        if (AutomationCoordinator.activeMacro.value.id == saved.id) {
            AutomationCoordinator.selectMacro(saved)
        }
        return saved
    }

    fun createNewMacro(name: String = "Macro Baru", initialActions: List<Action> = emptyList()): Macro {
        val s = settings.value
        val macro = Macro(
            name = name,
            actions = initialActions,
            loopCount = 1,
            loopDelayMs = 500L,
            jitterPx = s.defaultJitterPx
        )
        return store.saveMacro(macro)
    }

    fun createQuickAutoClicker(screenW: Int, screenH: Int): Macro {
        val s = settings.value
        val macro = Macro(
            name = "Auto Clicker",
            loopCount = 0, // Infinite until stopped
            loopDelayMs = 150L,
            jitterPx = s.defaultJitterPx,
            actions = listOf(
                ClickAction(x = screenW / 2, y = screenH / 2, durationMs = s.defaultTapDurationMs, delayAfterMs = 200L)
            )
        )
        return store.saveMacro(macro)
    }

    fun duplicateMacro(macro: Macro) {
        store.duplicateMacro(macro)
        _messages.tryEmit("Macro diduplikat.")
    }

    fun deleteMacro(id: String) {
        store.deleteMacro(id)
        _messages.tryEmit("Macro dihapus.")
    }

    fun effectiveNowMs(): Long = store.effectiveNowMs()

    fun macroIconPath(logoImage: String): String? = store.macroIconPath(logoImage)

    fun setMacroLogoFromUri(macro: Macro, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    } ?: error("Format foto tidak didukung.")
                    val iconName = try {
                        store.saveMacroLogo(macro.id, bmp)
                    } finally {
                        bmp.recycle()
                    }
                    val latest = store.getMacroById(macro.id) ?: macro
                    store.saveMacro(latest.copy(logoImage = iconName))
                }
            }
            result.onSuccess { saved ->
                if (AutomationCoordinator.activeMacro.value.id == saved.id) {
                    AutomationCoordinator.selectMacro(saved)
                }
                _messages.emit("Foto logo '${saved.name}' diperbarui.")
            }.onFailure { err ->
                _messages.emit("Gagal memasang foto logo: ${err.message}")
            }
        }
    }

    fun setMacroLogoFromLibrary(macro: Macro, libraryImageName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val iconName = store.copyLibraryImageAsMacroLogo(macro.id, libraryImageName)
                        ?: error("Gambar tidak ditemukan di pustaka.")
                    val latest = store.getMacroById(macro.id) ?: macro
                    store.saveMacro(latest.copy(logoImage = iconName))
                }
            }
            result.onSuccess { saved ->
                if (AutomationCoordinator.activeMacro.value.id == saved.id) {
                    AutomationCoordinator.selectMacro(saved)
                }
                _messages.emit("Foto logo '${saved.name}' diperbarui.")
            }.onFailure { err ->
                _messages.emit("Gagal memasang foto logo: ${err.message}")
            }
        }
    }

    fun removeMacroLogo(macro: Macro) {
        val prev = macro.logoImage
        val latest = store.getMacroById(macro.id) ?: macro
        val saved = saveMacro(latest.copy(logoImage = ""))
        if (prev.isNotBlank()) {
            store.cleanupUnusedMacroIcon(prev, excludingMacroId = saved.id)
        }
        _messages.tryEmit("Foto logo '${saved.name}' dihapus.")
    }

    // ---- Export / Import ----

    fun exportMacroToUri(macro: Macro, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        store.exportMacroZip(macro, out)
                    } ?: error("Tidak dapat membuka file tujuan.")
                }
            }
            result.onSuccess { summary ->
                val imgNote = if (summary.imagesExported > 0) {
                    "${summary.imagesExported} file gambar"
                } else {
                    "tanpa gambar"
                }
                val macroNote = if (summary.macrosExported > 1) {
                    "${summary.macrosExported} macro (termasuk sub-macro)"
                } else {
                    "1 macro"
                }
                _messages.emit("Diekspor '${macro.name}': $macroNote & $imgNote (.zip).")
            }.onFailure { err ->
                _messages.emit("Gagal ekspor: ${err.message}")
            }
        }
    }

    fun exportAllBackupToUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        store.exportAllBackupZip(out)
                    } ?: error("Tidak dapat membuka file backup.")
                }
            }
            result.onSuccess { summary ->
                _messages.emit("Backup selesai: ${summary.macrosExported} macro & ${summary.imagesExported} file gambar (.zip).")
            }.onFailure { err ->
                _messages.emit("Gagal backup: ${err.message}")
            }
        }
    }

    fun exportImageToUri(imageName: String, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ok = appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        store.exportImagePng(imageName, out)
                    } ?: error("Tidak dapat membuka file tujuan.")
                    if (!ok) error("File gambar '$imageName' tidak ditemukan.")
                }
            }
            if (result.isSuccess) _messages.emit("Gambar '${imageName.substringAfterLast('/')}' disimpan (.png).")
            else _messages.emit("Gagal menyimpan gambar: ${result.exceptionOrNull()?.message}")
        }
    }

    fun exportImagesZipToUri(imageNames: Collection<String>, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        store.exportImagesZip(imageNames, out)
                    } ?: error("Tidak dapat membuka file tujuan.")
                }
            }
            result.onSuccess { count ->
                _messages.emit("$count gambar diekspor (.zip).")
            }.onFailure { err ->
                _messages.emit("Gagal ekspor gambar: ${err.message}")
            }
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        store.importFromStream(input)
                    } ?: error("File tidak dapat dibaca.")
                }
            }
            result.onSuccess { s ->
                _messages.emit("Impor selesai: ${s.macrosImported} macro, ${s.scriptsImported} script, ${s.imagesImported} gambar.")
            }.onFailure { err ->
                _messages.emit("Gagal impor: ${err.message}")
            }
        }
    }

    // ---- Images & Folders ----

    private fun syncActiveMacroFromStore() {
        val activeId = AutomationCoordinator.activeMacro.value.id
        store.getMacroById(activeId)?.let { AutomationCoordinator.selectMacro(it) }
    }

    private fun queryDisplayName(uri: Uri): String {
        val raw = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return raw?.substringBeforeLast('.')?.trim().takeIf { !it.isNullOrBlank() } ?: "gambar"
    }

    fun importImageFromUri(uri: Uri, desiredName: String = "gambar", folder: String = "") {
        importImagesFromUris(listOf(uri), folder = folder, fallbackName = desiredName)
    }

    fun importImagesFromUris(uris: List<Uri>, folder: String = "", fallbackName: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val (addedAssets, failedCount) = withContext(Dispatchers.IO) {
                val added = ArrayList<ImageAsset>()
                var failed = 0
                for (uri in uris) {
                    val ok = runCatching {
                        val name = fallbackName ?: queryDisplayName(uri)
                        val bmp = appContext.contentResolver.openInputStream(uri)?.use { input ->
                            BitmapFactory.decodeStream(input)
                        } ?: error("Format gambar tidak didukung.")
                        try {
                            store.saveImage(name, bmp, folder)
                        } finally {
                            bmp.recycle()
                        }
                    }
                    ok.onSuccess { added += it }.onFailure { failed++ }
                }
                added to failed
            }

            val destLabel = folder.ifBlank { "Utama" }
            when {
                addedAssets.isEmpty() -> _messages.emit("Gagal mengimpor gambar (format tidak didukung).")
                addedAssets.size == 1 && failedCount == 0 -> _messages.emit("Gambar '${addedAssets.first().displayName}' ditambahkan.")
                failedCount == 0 -> _messages.emit("${addedAssets.size} gambar berhasil ditambahkan ke '$destLabel'.")
                else -> _messages.emit("${addedAssets.size} gambar ditambahkan ($failedCount gagal dibaca).")
            }
        }
    }

    fun recropImage(existingName: String, cropped: android.graphics.Bitmap) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    try {
                        store.overwriteImage(existingName, cropped)
                    } finally {
                        cropped.recycle()
                    }
                }
            }
            result.onSuccess { asset ->
                _messages.emit("Gambar '${asset.displayName}' dipotong ulang (${asset.width}×${asset.height}).")
            }.onFailure { err ->
                _messages.emit("Gagal potong ulang: ${err.message}")
            }
        }
    }

    fun createImageFolder(rawName: String) {
        val created = store.createImageFolder(rawName)
        if (created != null) _messages.tryEmit("Folder '$created' dibuat.")
        else _messages.tryEmit("Nama folder tidak valid.")
    }

    fun deleteImageFolder(folderName: String) {
        if (store.deleteImageFolder(folderName)) {
            syncActiveMacroFromStore()
            _messages.tryEmit("Folder '$folderName' dihapus (gambar dipindahkan ke Utama).")
        } else {
            _messages.tryEmit("Gagal menghapus folder '$folderName'.")
        }
    }

    fun moveImages(names: Collection<String>, targetFolder: String) {
        val moved = store.moveImages(names, targetFolder)
        syncActiveMacroFromStore()
        val destLabel = targetFolder.ifBlank { "Utama" }
        if (moved > 0) _messages.tryEmit("$moved gambar dipindahkan ke '$destLabel'.")
        else _messages.tryEmit("Gambar sudah berada di '$destLabel'.")
    }

    fun renameImage(oldName: String, newName: String) {
        val renamed = store.renameImage(oldName, newName)
        if (renamed != null) {
            syncActiveMacroFromStore()
            _messages.tryEmit("Diubah menjadi '${renamed.substringAfterLast('/')}'.")
        } else {
            _messages.tryEmit("Gagal mengubah nama gambar.")
        }
    }

    fun deleteImage(name: String) {
        store.deleteImage(name)
        _messages.tryEmit("Gambar '${name.substringAfterLast('/')}' dihapus.")
    }

    fun deleteImages(names: Collection<String>) {
        val count = store.deleteImages(names)
        if (count > 0) _messages.tryEmit("$count gambar dihapus.")
    }

    // ---- Lua Scripts ----

    fun saveScript(script: LuaScript): LuaScript {
        val saved = store.saveScript(script)
        if (AutomationCoordinator.activeLuaScript.value?.id == saved.id) {
            AutomationCoordinator.selectLuaScript(saved)
        }
        _messages.tryEmit("Script '${saved.name}' disimpan.")
        return saved
    }

    fun createNewScript(): LuaScript {
        return store.saveScript(
            LuaScript(
                name = "Script Baru",
                code = LocalStore.DEFAULT_LUA_SAMPLE
            )
        )
    }

    fun deleteScript(id: String) {
        store.deleteScript(id)
        _messages.tryEmit("Script dihapus.")
    }

    fun validateLuaSyntax(code: String) {
        _syntaxResult.value = SandboxedLuaEngine.validateSyntax(code)
    }

    fun clearSyntaxResult() {
        _syntaxResult.value = null
    }

    // ---- Settings ----

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        store.saveSettings(transform(settings.value))
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MacroViewModel(context.applicationContext) as T
    }
}
