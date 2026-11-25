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
import com.example.data.MacroSet
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
    val macroSets: StateFlow<List<MacroSet>> = store.macroSets
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

    fun createNewMacro(
        name: String = "Macro Baru",
        initialActions: List<Action> = emptyList(),
        setId: String = ""
    ): Macro {
        val s = settings.value
        val targetSet = if (setId.isNotBlank()) store.getMacroSetById(setId) else null
        var macro = Macro(
            name = name,
            actions = initialActions,
            loopCount = 1,
            loopDelayMs = 500L,
            jitterPx = s.defaultJitterPx,
            setId = targetSet?.id.orEmpty()
        )
        if (targetSet != null &&
            com.example.data.MacroSecurity.hasAnySetProtection(targetSet) &&
            com.example.data.MacroSecurity.hasSetPassword(targetSet)
        ) {
            macro = com.example.data.MacroSecurity.syncMacroWithSetSecurity(macro, targetSet)
        }
        return store.saveMacro(macro)
    }

    fun createQuickAutoClicker(screenW: Int, screenH: Int, setId: String = ""): Macro {
        val s = settings.value
        val macro = Macro(
            name = "Auto Clicker",
            loopCount = 0, // Infinite until stopped
            loopDelayMs = 150L,
            jitterPx = s.defaultJitterPx,
            sourceWidth = screenW.coerceAtLeast(0),
            sourceHeight = screenH.coerceAtLeast(0),
            setId = setId,
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

    // ---- Macro Sets ----

    fun createMacroSet(
        name: String,
        description: String = "",
        colorHex: String = "#22D3EE",
        initialMemberIds: Set<String> = emptySet()
    ): MacroSet {
        val cleanName = name.trim().ifBlank { "Set Baru" }
        val created = store.saveMacroSet(
            MacroSet(
                name = cleanName,
                description = description.trim(),
                colorHex = colorHex
            )
        )
        if (initialMemberIds.isNotEmpty()) {
            store.setMacrosInSet(created.id, initialMemberIds)
            syncActiveMacroFromStore()
        }
        val countNote = if (initialMemberIds.isNotEmpty()) " (${initialMemberIds.size} macro)" else ""
        _messages.tryEmit("Macro Set '${created.name}' dibuat$countNote.")
        return created
    }

    fun updateMacroSetInfo(set: MacroSet, name: String, description: String, colorHex: String): MacroSet {
        val latest = store.getMacroSetById(set.id) ?: set
        val saved = store.saveMacroSet(
            latest.copy(
                name = name.trim().ifBlank { latest.name },
                description = description.trim(),
                colorHex = colorHex
            )
        )
        _messages.tryEmit("Macro Set '${saved.name}' diperbarui.")
        return saved
    }

    fun saveMacroSetSecurity(updatedSet: MacroSet): MacroSet {
        val saved = store.saveMacroSetSecurity(updatedSet)
        syncActiveMacroFromStore()
        val memberCount = store.getMacrosInSet(saved.id).size
        val summary = buildList {
            if (saved.isLocked) add("Terkunci")
            if (saved.expiresAtEpochMs > 0L) add("Tenggat ${saved.expiryLabel}")
        }.joinToString(", ")
        if (summary.isBlank()) {
            _messages.tryEmit("Semua proteksi Set '${saved.name}' dimatikan ($memberCount macro ikut dibuka).")
        } else {
            _messages.tryEmit("Proteksi Set '${saved.name}' disimpan: $summary ($memberCount macro).")
        }
        return saved
    }

    fun setMacrosInSet(set: MacroSet, selectedMacroIds: Set<String>) {
        store.setMacrosInSet(set.id, selectedMacroIds)
        syncActiveMacroFromStore()
        _messages.tryEmit("Isi Set '${set.name}' diperbarui (${selectedMacroIds.size} macro).")
    }

    fun moveMacroToSet(macro: Macro, targetSetId: String) {
        val saved = store.moveMacroToSet(macro.id, targetSetId) ?: return
        syncActiveMacroFromStore()
        if (targetSetId.isBlank()) {
            _messages.tryEmit("'${saved.name}' dikeluarkan dari Macro Set.")
        } else {
            val targetName = store.getMacroSetById(targetSetId)?.name ?: "Set"
            _messages.tryEmit("'${saved.name}' dipindahkan ke Set '$targetName'.")
        }
    }

    fun duplicateMacroSet(set: MacroSet) {
        val copy = store.duplicateMacroSet(set)
        val count = store.getMacrosInSet(copy.id).size
        _messages.tryEmit("Set '${set.name}' diduplikat ($count macro).")
    }

    fun deleteMacroSet(set: MacroSet, deleteMembers: Boolean) {
        store.deleteMacroSet(set.id, deleteMembers = deleteMembers)
        syncActiveMacroFromStore()
        val msg = if (deleteMembers) {
            "Macro Set '${set.name}' beserta semua macro di dalamnya dihapus."
        } else {
            "Macro Set '${set.name}' dihapus (macro tetap aman di daftar utama)."
        }
        _messages.tryEmit(msg)
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

    fun exportMacroToUri(macro: Macro, uri: Uri, singleUseImport: Boolean = false) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        store.exportMacroZip(macro, out, singleUseImport = singleUseImport)
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
                val singleTag = if (summary.singleUseImport) " • 🔒 1× Impor" else ""
                _messages.emit("Diekspor '${macro.name}': $macroNote & $imgNote$singleTag (.zip).")
            }.onFailure { err ->
                _messages.emit("Gagal ekspor: ${err.message}")
            }
        }
    }

    fun exportMacroToDownloads(macro: Macro, fileName: String, singleUseImport: Boolean = false) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.data.CloudSafeStorage.writeToDownloads(
                        context = appContext,
                        desiredFileName = fileName,
                        mimeType = "application/zip"
                    ) { out ->
                        store.exportMacroZip(macro, out, singleUseImport = singleUseImport)
                    }
                }
            }
            result.onSuccess { (saved, summary) ->
                val imgNote = if (summary.imagesExported > 0) "${summary.imagesExported} gambar" else "tanpa gambar"
                val singleTag = if (summary.singleUseImport) " • 🔒 1× Impor" else ""
                _messages.emit("Tersimpan di ${saved.displayPath} (${summary.macrosExported} macro, $imgNote$singleTag).")
            }.onFailure { err ->
                _messages.emit("Gagal ekspor ke Download: ${err.message}")
            }
        }
    }

    fun exportMacroSetToUri(set: MacroSet, uri: Uri, singleUseImport: Boolean = false) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        store.exportMacroSetZip(set, out, singleUseImport = singleUseImport)
                    } ?: error("Tidak dapat membuka file tujuan.")
                }
            }
            result.onSuccess { summary ->
                val imgNote = if (summary.imagesExported > 0) "${summary.imagesExported} gambar" else "tanpa gambar"
                val lockTag = if (set.isLocked) " • 🔒 Terkunci" else ""
                val singleTag = if (summary.singleUseImport) " • 1× Impor" else ""
                _messages.emit("Set '${set.name}' diekspor: ${summary.macrosExported} macro & $imgNote$lockTag$singleTag (.zip).")
            }.onFailure { err ->
                _messages.emit("Gagal ekspor set: ${err.message}")
            }
        }
    }

    fun exportMacroSetToDownloads(set: MacroSet, fileName: String, singleUseImport: Boolean = false) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.data.CloudSafeStorage.writeToDownloads(
                        context = appContext,
                        desiredFileName = fileName,
                        mimeType = "application/zip"
                    ) { out ->
                        store.exportMacroSetZip(set, out, singleUseImport = singleUseImport)
                    }
                }
            }
            result.onSuccess { (saved, summary) ->
                val imgNote = if (summary.imagesExported > 0) "${summary.imagesExported} gambar" else "tanpa gambar"
                val lockTag = if (set.isLocked) " • 🔒 Terkunci" else ""
                val singleTag = if (summary.singleUseImport) " • 1× Impor" else ""
                _messages.emit("Set '${set.name}' tersimpan di ${saved.displayPath} (${summary.macrosExported} macro, $imgNote$lockTag$singleTag).")
            }.onFailure { err ->
                _messages.emit("Gagal ekspor set ke Download: ${err.message}")
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
                val setNote = if (summary.setsExported > 0) "${summary.setsExported} set, " else ""
                _messages.emit("Backup selesai: $setNote${summary.macrosExported} macro & ${summary.imagesExported} file gambar (.zip).")
            }.onFailure { err ->
                _messages.emit("Gagal backup: ${err.message}")
            }
        }
    }

    fun exportAllBackupToDownloads(fileName: String = "wjust_macro_backup.zip") {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.data.CloudSafeStorage.writeToDownloads(
                        context = appContext,
                        desiredFileName = fileName,
                        mimeType = "application/zip"
                    ) { out ->
                        store.exportAllBackupZip(out)
                    }
                }
            }
            result.onSuccess { (saved, summary) ->
                val setNote = if (summary.setsExported > 0) "${summary.setsExported} set, " else ""
                _messages.emit("Backup tersimpan di ${saved.displayPath} ($setNote${summary.macrosExported} macro & ${summary.imagesExported} gambar).")
            }.onFailure { err ->
                _messages.emit("Gagal backup ke Download: ${err.message}")
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

    fun exportImageToDownloads(imageName: String, fileName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.data.CloudSafeStorage.writeToDownloads(
                        context = appContext,
                        desiredFileName = fileName,
                        mimeType = "image/png"
                    ) { out ->
                        val ok = store.exportImagePng(imageName, out)
                        if (!ok) error("File gambar '$imageName' tidak ditemukan.")
                    }
                }
            }
            result.onSuccess { (saved, _) ->
                _messages.emit("Gambar disimpan di ${saved.displayPath}.")
            }.onFailure { err ->
                _messages.emit("Gagal menyimpan gambar: ${err.message}")
            }
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

    fun exportImagesZipToDownloads(imageNames: Collection<String>, fileName: String = "gambar_wjust_macro.zip") {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.data.CloudSafeStorage.writeToDownloads(
                        context = appContext,
                        desiredFileName = fileName,
                        mimeType = "application/zip"
                    ) { out ->
                        store.exportImagesZip(imageNames, out)
                    }
                }
            }
            result.onSuccess { (saved, count) ->
                _messages.emit("$count gambar tersimpan di ${saved.displayPath}.")
            }.onFailure { err ->
                _messages.emit("Gagal ekspor gambar: ${err.message}")
            }
        }
    }

    fun loadLocalImportFiles(onLoaded: (List<com.example.data.CloudSafeStorage.LocalBackupEntry>) -> Unit) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                com.example.data.CloudSafeStorage.listLocalImportFiles(appContext)
            }
            onLoaded(files)
        }
    }

    fun importFromLocalBackup(entry: com.example.data.CloudSafeStorage.LocalBackupEntry) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.data.CloudSafeStorage.openImportStream(appContext, entry)?.use { input ->
                        store.importFromStream(input)
                    } ?: error("File '${entry.displayName}' tidak dapat dibaca.")
                }
            }
            result.onSuccess { s ->
                syncActiveMacroFromStore()
                val msg = buildString {
                    append("Impor '${entry.displayName}' selesai: ")
                    if (s.setsImported > 0) {
                        append("${s.setsImported} set")
                        if (s.setsOverwritten > 0) append(" (${s.setsOverwritten} ditimpa)")
                        append(", ")
                    }
                    append("${s.macrosImported} macro")
                    if (s.macrosOverwritten > 0) append(" (${s.macrosOverwritten} ditimpa)")
                    if (s.deadlinesExtended > 0) append(" • tenggat diperpanjang +waktu")
                    if (s.singleUseImport) append(" • 🔒 1× impor (kini sudah terpakai)")
                    if (s.scriptsImported > 0) append(", ${s.scriptsImported} script")
                    if (s.imagesImported > 0) append(", ${s.imagesImported} gambar")
                    append(".")
                }
                _messages.emit(msg)
            }.onFailure { err ->
                _messages.emit("Gagal impor '${entry.displayName}': ${err.message}")
            }
        }
    }

    fun toggleMacroPassiveEnabled(macro: Macro) {
        if (!macro.isPassive) return
        val parentSet = store.getMacroSetById(macro.setId)
        val now = effectiveNowMs()
        if (com.example.data.MacroSecurity.isTampered(macro) || (parentSet != null && com.example.data.MacroSecurity.isSetTampered(parentSet))) {
            _messages.tryEmit(com.example.data.MacroSecurity.denialMessage(macro, com.example.data.RunDenialReason.TAMPERED, parentSet))
            return
        }
        if (com.example.data.MacroSecurity.isExpired(macro, now) || (parentSet != null && com.example.data.MacroSecurity.isSetExpired(parentSet, now))) {
            _messages.tryEmit(com.example.data.MacroSecurity.denialMessage(macro, com.example.data.RunDenialReason.EXPIRED, parentSet))
            return
        }
        val nextEnabled = !macro.passiveEnabled
        val updated = com.example.data.MacroSecurity.togglePassiveEnabled(macro, nextEnabled)
        saveMacro(updated)
        val statusText = if (nextEnabled) "ON (dapat ditarik macro aktif)" else "OFF (tidak dapat ditarik / diskip)"
        _messages.tryEmit("Mode Pasif '${updated.name}' diubah ke $statusText.")
    }

    fun updateMacroAutoScale(
        macro: Macro,
        autoScaleResolution: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
        scaleMode: com.example.data.ResolutionScaleMode
    ) {
        if (com.example.data.MacroSecurity.isTampered(macro)) {
            _messages.tryEmit(com.example.data.MacroSecurity.denialMessage(macro, com.example.data.RunDenialReason.TAMPERED))
            return
        }
        val latest = store.getMacroById(macro.id) ?: macro
        val updated = com.example.data.MacroSecurity.updateAutoScale(
            macro = latest,
            autoScaleResolution = autoScaleResolution,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            scaleMode = scaleMode
        )
        saveMacro(updated)
        val status = if (updated.autoScaleResolution) {
            if (updated.sourceWidth > 0 && updated.sourceHeight > 0) {
                "ON (${updated.sourceWidth}×${updated.sourceHeight} • ${updated.scaleMode.label})"
            } else {
                "ON (${updated.scaleMode.label})"
            }
        } else {
            "OFF"
        }
        _messages.tryEmit("Auto-Scale '${updated.name}': $status.")
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
                syncActiveMacroFromStore()
                val msg = buildString {
                    append("Impor selesai: ")
                    if (s.setsImported > 0) {
                        append("${s.setsImported} set")
                        if (s.setsOverwritten > 0) append(" (${s.setsOverwritten} ditimpa)")
                        append(", ")
                    }
                    append("${s.macrosImported} macro")
                    if (s.macrosOverwritten > 0) append(" (${s.macrosOverwritten} ditimpa)")
                    if (s.deadlinesExtended > 0) append(" • tenggat diperpanjang +waktu")
                    if (s.singleUseImport) append(" • 🔒 1× impor (kini sudah terpakai)")
                    if (s.scriptsImported > 0) append(", ${s.scriptsImported} script")
                    if (s.imagesImported > 0) append(", ${s.imagesImported} gambar")
                    append(".")
                }
                _messages.emit(msg)
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
