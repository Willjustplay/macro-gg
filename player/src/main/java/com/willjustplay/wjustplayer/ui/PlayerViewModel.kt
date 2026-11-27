package com.willjustplay.wjustplayer.ui

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AccountProfile
import com.example.data.Macro
import com.example.data.MacroSet
import com.willjustplay.wjustplayer.data.PlayerAccountStore
import com.willjustplay.wjustplayer.data.PlayerMacroAccounts
import com.willjustplay.wjustplayer.data.PlayerStorageHelper
import com.willjustplay.wjustplayer.data.PlayerStore
import com.willjustplay.wjustplayer.runtime.PlayerAccessibilityService
import com.willjustplay.wjustplayer.runtime.PlayerCoordinator
import com.willjustplay.wjustplayer.runtime.PlayerSafeLogBus
import com.willjustplay.wjustplayer.runtime.PlayerScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerPermissions(
    val accessibilityEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val screenCaptureActive: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false
)

class PlayerViewModel(private val appContext: Context) : ViewModel() {

    private val store = PlayerStore.get(appContext)
    private val accountStore = PlayerAccountStore.get(appContext)

    val macros: StateFlow<List<Macro>> = store.macros
    val macroSets: StateFlow<List<MacroSet>> = store.macroSets
    val accountsByKey: StateFlow<Map<String, PlayerMacroAccounts>> = accountStore.accountsByKey

    private val _permissions = MutableStateFlow(PlayerPermissions())
    val permissions: StateFlow<PlayerPermissions> = _permissions.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        PlayerCoordinator.attach(appContext)
        refreshPermissions(appContext)
    }

    fun refreshPermissions(context: Context = appContext) {
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        _permissions.value = PlayerPermissions(
            accessibilityEnabled = PlayerAccessibilityService.isServiceEnabled(context) ||
                PlayerAccessibilityService.isConnected.value,
            overlayEnabled = Settings.canDrawOverlays(context),
            screenCaptureActive = PlayerScreenCaptureService.isCapturing.value,
            batteryOptimizationIgnored = ignoringBattery
        )
    }

    fun effectiveNowMs(): Long = store.effectiveNowMs()

    fun macroIconPath(iconName: String): String? = store.macroIconPath(iconName)

    fun accountKeyFor(macro: Macro): String = accountStore.keyFor(macro)

    fun accountsFor(macro: Macro): List<AccountProfile> = accountStore.getFor(macro).accounts

    fun saveMacroAccounts(macro: Macro, accounts: List<AccountProfile>) {
        accountStore.saveAccounts(macro, accounts)
        val activeCount = accounts.count { it.enabled }
        PlayerSafeLogBus.info("Daftar akun lokal '${macro.name}' disimpan ($activeCount akun aktif).")
        _messages.tryEmit("Akun lokal '${macro.name}' disimpan ($activeCount aktif).")
    }

    fun togglePassive(macro: Macro) {
        val updated = store.togglePassiveEnabled(macro.id) ?: return
        val status = if (updated.passiveEnabled) "ON" else "OFF"
        PlayerSafeLogBus.info("Sub-macro '${updated.name}': $status.")
        _messages.tryEmit("'${updated.name}' diubah ke $status.")
    }

    fun deleteMacro(macro: Macro) {
        if (PlayerCoordinator.activeMacro.value?.id == macro.id) {
            PlayerCoordinator.stop()
        }
        store.deleteMacro(macro.id)
        _messages.tryEmit("Macro '${macro.name}' dihapus.")
    }

    fun deleteMacroSet(set: MacroSet, deleteMembers: Boolean) {
        PlayerCoordinator.stop()
        store.deleteMacroSet(set.id, deleteMembers = deleteMembers)
        _messages.tryEmit("Macro Set '${set.name}' dihapus.")
    }

    fun loadLocalWjmFiles(onLoaded: (List<PlayerStorageHelper.LocalWjmEntry>) -> Unit) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                PlayerStorageHelper.listLocalWjmFiles(appContext)
            }
            onLoaded(files)
        }
    }

    fun importWjmFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        store.importWjmFromStream(stream)
                    } ?: error("Tidak dapat membaca file .wjm.")
                }
            }
            emitImportOutcome(result)
        }
    }

    fun importWjmFromLocalEntry(entry: PlayerStorageHelper.LocalWjmEntry) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PlayerStorageHelper.openStream(appContext, entry)?.use { stream ->
                        store.importWjmFromStream(stream)
                    } ?: error("Tidak dapat membuka '${entry.displayName}'.")
                }
            }
            emitImportOutcome(result)
        }
    }

    private suspend fun emitImportOutcome(result: Result<PlayerStore.ImportSummary>) {
        result.onSuccess { summary ->
            val parts = buildList {
                if (summary.setsImported > 0) add("${summary.setsImported} Macro Set")
                if (summary.macrosImported > 0) add("${summary.macrosImported} macro")
                if (summary.deadlinesExtended > 0) add("masa aktif diperpanjang")
                if (summary.singleUseImport) add("1× Impor")
            }
            val msg = "Berhasil impor .wjm: ${parts.joinToString(" • ")}."
            PlayerSafeLogBus.info(msg)
            _messages.emit(msg)
        }.onFailure { err ->
            val msg = err.message ?: "File bukan format terenkripsi .wjm yang valid."
            PlayerSafeLogBus.error("Impor gagal: $msg")
            _messages.emit(msg)
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(context.applicationContext) as T
    }
}
