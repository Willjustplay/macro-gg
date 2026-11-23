package com.example.ui

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.automation.AutomationCoordinator
import com.example.automation.ExecutionLogBus
import com.example.automation.LuaSyntaxResult
import com.example.automation.RunSummary
import com.example.automation.SandboxedLuaEngine
import com.example.automation.ScreenColorSamplerService
import com.example.automation.TapLuaAccessibilityService
import com.example.data.LuaScript
import com.example.data.MacroStep
import com.example.data.MacroStepType
import com.example.data.RunHistoryEntry
import com.example.data.RunTargetType
import com.example.data.TapLuaRepository
import com.example.data.ThemeMode
import com.example.data.UiState
import com.example.data.UserProfile
import com.example.data.VisualMacro
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PermissionHealth(
    val accessibilityEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val colorCaptureActive: Boolean = false
)

class TapLuaViewModel(
    private val repository: TapLuaRepository
) : ViewModel() {

    private val _permissionHealth = MutableStateFlow(PermissionHealth())
    val permissionHealth: StateFlow<PermissionHealth> = _permissionHealth.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(true)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    private val _syntaxResult = MutableStateFlow<LuaSyntaxResult?>(null)
    val syntaxResult: StateFlow<LuaSyntaxResult?> = _syntaxResult.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    val userProfileState: StateFlow<UiState<UserProfile?>> = repository.observeUserProfile()
        .map<UserProfile?, UiState<UserProfile?>> { profile ->
            if (profile != null) {
                AutomationCoordinator.updateUserTouchPreferences(
                    profile.defaultTapDurationMs,
                    profile.jitterRadiusPx
                )
            }
            UiState.Success(profile)
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(UiState.Error(rootError.message ?: "Gagal memuat profil pengguna"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    val macrosState: StateFlow<UiState<List<VisualMacro>>> = repository.observeMacros()
        .map<List<VisualMacro>, UiState<List<VisualMacro>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(UiState.Error(rootError.message ?: "Gagal memuat daftar macro"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    val scriptsState: StateFlow<UiState<List<LuaScript>>> = repository.observeScripts()
        .map<List<LuaScript>, UiState<List<LuaScript>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(UiState.Error(rootError.message ?: "Gagal memuat daftar script Lua"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    val runHistoryState: StateFlow<UiState<List<RunHistoryEntry>>> = repository.observeRunHistory()
        .map<List<RunHistoryEntry>, UiState<List<RunHistoryEntry>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(UiState.Error(rootError.message ?: "Gagal memuat riwayat eksekusi"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    init {
        viewModelScope.launch {
            repository.ensureUserProfile()
        }
        AutomationCoordinator.setRunFinishedListener { summary ->
            recordRunSummary(summary)
        }
    }

    override fun onCleared() {
        AutomationCoordinator.setRunFinishedListener(null)
        super.onCleared()
    }

    fun refreshPermissions(context: Context) {
        _permissionHealth.value = PermissionHealth(
            accessibilityEnabled = TapLuaAccessibilityService.isServiceEnabled(context) ||
                TapLuaAccessibilityService.isConnected.value,
            overlayEnabled = Settings.canDrawOverlays(context),
            colorCaptureActive = ScreenColorSamplerService.isCapturing.value
        )
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        _dynamicColorEnabled.value = enabled
    }

    fun saveUserPreferences(
        displayName: String,
        themeMode: ThemeMode,
        defaultTapDurationMs: Int,
        jitterRadiusPx: Int
    ) {
        viewModelScope.launch {
            val result = repository.updatePreferences(
                displayName = displayName,
                themeMode = themeMode,
                defaultTapDurationMs = defaultTapDurationMs,
                jitterRadiusPx = jitterRadiusPx
            )
            if (result.isSuccess) {
                AutomationCoordinator.updateUserTouchPreferences(defaultTapDurationMs, jitterRadiusPx)
                _snackbarMessages.tryEmit("Preferensi disimpan ke cloud.")
            } else {
                _snackbarMessages.tryEmit(result.exceptionOrNull()?.message ?: "Gagal menyimpan preferensi.")
            }
        }
    }

    fun saveMacro(
        macroId: String?,
        name: String,
        description: String,
        loopCount: Int,
        loopDelayMs: Int,
        stopOnError: Boolean,
        steps: List<MacroStep>,
        onSaved: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = repository.saveMacro(
                macroId = macroId,
                name = name,
                description = description,
                loopCount = loopCount,
                loopDelayMs = loopDelayMs,
                stopOnError = stopOnError,
                steps = steps
            )
            result.onSuccess { savedId ->
                _snackbarMessages.tryEmit("Macro '$name' tersinkronisasi ke cloud.")
                onSaved(savedId)
            }.onFailure { err ->
                _snackbarMessages.tryEmit(err.message ?: "Gagal menyimpan macro.")
            }
        }
    }

    fun deleteMacro(macroId: String) {
        viewModelScope.launch {
            val result = repository.deleteMacro(macroId)
            if (result.isSuccess) {
                _snackbarMessages.tryEmit("Macro dihapus.")
            } else {
                _snackbarMessages.tryEmit(result.exceptionOrNull()?.message ?: "Gagal menghapus macro.")
            }
        }
    }

    fun createStarterMacro(onCreated: (String) -> Unit = {}) {
        val starterSteps = listOf(
            MacroStep(
                type = MacroStepType.WAIT_COLOR,
                label = "Cek Tombol Siap",
                x = 540,
                y = 1280,
                targetColorHex = "#2DD4BF",
                colorTolerance = 20,
                timeoutMs = 5000,
                delayAfterMs = 200
            ),
            MacroStep(
                type = MacroStepType.CLICK,
                label = "Klik Konfirmasi",
                x = 540,
                y = 1280,
                durationMs = 60,
                delayAfterMs = 600
            ),
            MacroStep(
                type = MacroStepType.SWIPE,
                label = "Geser Halaman ke Atas",
                x = 540,
                y = 1600,
                endX = 540,
                endY = 700,
                durationMs = 350,
                delayAfterMs = 500
            )
        )
        saveMacro(
            macroId = null,
            name = "Template Auto-Tap & Warna",
            description = "Menunggu warna piksel target lalu klik dan swipe otomatis.",
            loopCount = 3,
            loopDelayMs = 1000,
            stopOnError = true,
            steps = starterSteps,
            onSaved = onCreated
        )
    }

    fun saveScript(
        scriptId: String?,
        title: String,
        description: String,
        code: String,
        tags: List<String>,
        onSaved: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = repository.saveScript(
                scriptId = scriptId,
                title = title,
                description = description,
                code = code,
                tags = tags
            )
            result.onSuccess { savedId ->
                _snackbarMessages.tryEmit("Script Lua '$title' tersimpan di cloud.")
                onSaved(savedId)
            }.onFailure { err ->
                _snackbarMessages.tryEmit(err.message ?: "Gagal menyimpan script Lua.")
            }
        }
    }

    fun deleteScript(scriptId: String) {
        viewModelScope.launch {
            val result = repository.deleteScript(scriptId)
            if (result.isSuccess) {
                _snackbarMessages.tryEmit("Script Lua dihapus.")
            } else {
                _snackbarMessages.tryEmit(result.exceptionOrNull()?.message ?: "Gagal menghapus script.")
            }
        }
    }

    fun createStarterLuaScript(onCreated: (String) -> Unit = {}) {
        val sampleCode = """
            |-- TapLua Starter: Deteksi Warna & Klik Presisi
            |local w, h = getScreenSize()
            |log("Layar aktif: " .. w .. "x" .. h)
            |
            |local targetX = math.floor(w / 2)
            |local targetY = math.floor(h * 0.55)
            |
            |for loop = 1, 3 do
            |    if not isRunning() then break end
            |    local hex, r, g, b = getColor(targetX, targetY)
            |    log("Loop #" .. loop .. " warna di pusat: " .. tostring(hex))
            |    click(targetX, targetY, 60)
            |    sleep(700)
            |end
            |
            |log("Eksekusi template selesai!")
        """.trimMargin()

        saveScript(
            scriptId = null,
            title = "Template Deteksi Warna & Klik",
            description = "Contoh script Lua kustom menggunakan getScreenSize, getColor, click, dan sleep.",
            code = sampleCode,
            tags = listOf("template", "warna", "klik"),
            onSaved = onCreated
        )
    }

    fun validateLuaSyntax(code: String) {
        val check = SandboxedLuaEngine.validateSyntax(code)
        _syntaxResult.value = check
        if (check.isValid) {
            ExecutionLogBus.lua("Dry Check OK: ${check.message}")
        } else {
            ExecutionLogBus.error("Dry Check Error: ${check.message}")
        }
    }

    fun clearSyntaxResult() {
        _syntaxResult.value = null
    }

    fun deleteRunHistory(runId: String) {
        viewModelScope.launch {
            repository.deleteRunHistory(runId)
        }
    }

    private fun recordRunSummary(summary: RunSummary) {
        viewModelScope.launch {
            repository.addRunHistory(
                targetType = summary.targetType,
                targetId = summary.targetId,
                targetName = summary.targetName,
                status = summary.status,
                durationMs = summary.durationMs,
                actionsExecuted = summary.actionsExecuted,
                summaryMessage = summary.summaryMessage
            )
            if (summary.targetType == RunTargetType.LUA && summary.targetId.isNotBlank()) {
                repository.updateScriptStatus(summary.targetId, summary.status)
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TapLuaViewModel(TapLuaRepository(context.applicationContext)) as T
        }
    }
}
