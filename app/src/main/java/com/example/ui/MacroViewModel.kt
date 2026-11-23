package com.example.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
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
            if (result.isSuccess) _messages.emit("Macro '${macro.name}' diekspor (.zip).")
            else _messages.emit("Gagal ekspor: ${result.exceptionOrNull()?.message}")
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
            if (result.isSuccess) _messages.emit("Semua data berhasil di-backup (.zip).")
            else _messages.emit("Gagal backup: ${result.exceptionOrNull()?.message}")
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

    // ---- Images ----

    fun importImageFromUri(uri: Uri, desiredName: String = "gambar") {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    } ?: error("Format gambar tidak didukung.")
                    try {
                        store.saveImage(desiredName, bmp)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            result.onSuccess { asset ->
                _messages.emit("Gambar '${asset.name}' ditambahkan.")
            }.onFailure { err ->
                _messages.emit("Gagal impor gambar: ${err.message}")
            }
        }
    }

    fun renameImage(oldName: String, newName: String) {
        val renamed = store.renameImage(oldName, newName)
        if (renamed != null) _messages.tryEmit("Diubah menjadi '$renamed'.")
        else _messages.tryEmit("Gagal mengubah nama gambar.")
    }

    fun deleteImage(name: String) {
        store.deleteImage(name)
        _messages.tryEmit("Gambar '$name' dihapus.")
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
