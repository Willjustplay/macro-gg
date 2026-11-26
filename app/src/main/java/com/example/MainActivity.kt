package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.automation.AutomationCoordinator
import com.example.automation.ExecutionLogBus
import com.example.automation.FloatingOverlayService
import com.example.automation.MacroAccessibilityService
import com.example.automation.ScreenCaptureService
import com.example.billing.BillingConfig
import com.example.billing.PremiumFeature
import com.example.billing.PremiumFeatureGate
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.example.data.MacroSet
import com.example.ui.AccessibilityDisclosureDialog
import com.example.ui.CreateOrEditMacroSetDialog
import com.example.ui.DeleteMacroSetDialog
import com.example.ui.ImageLibraryScreen
import com.example.ui.LiveLogPanel
import com.example.ui.LuaStudioScreen
import com.example.ui.MacroEditorScreen
import com.example.ui.MacroHomeScreen
import com.example.ui.MacroLogoPickerSheet
import com.example.ui.MacroSecuritySheet
import com.example.ui.MacroSetSecuritySheet
import com.example.ui.MacroViewModel
import com.example.ui.ManageMacroSetMembersSheet
import com.example.ui.MoveMacroToSetDialog
import com.example.ui.PasswordUnlockDialog
import com.example.ui.PremiumPaywallSheet
import com.example.ui.ProLockBadge
import com.example.ui.SettingsPanel
import com.example.ui.theme.TapLuaTheme
import kotlinx.coroutines.launch

private enum class MainTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    MACROS(R.string.nav_macros, Icons.Filled.TouchApp, Icons.Outlined.TouchApp),
    IMAGES(R.string.nav_images, Icons.Filled.ImageSearch, Icons.Outlined.ImageSearch),
    TOOLS(R.string.nav_tools, Icons.Filled.Code, Icons.Outlined.Code)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WjustMacroApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WjustMacroApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: MacroViewModel = viewModel(factory = MacroViewModel.Factory(context))

    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val macroSets by viewModel.macroSets.collectAsStateWithLifecycle()
    val scripts by viewModel.scripts.collectAsStateWithLifecycle()
    val images by viewModel.images.collectAsStateWithLifecycle()
    val imageFolders by viewModel.imageFolders.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val syntaxResult by viewModel.syntaxResult.collectAsStateWithLifecycle()
    val billingState by viewModel.billingState.collectAsStateWithLifecycle()
    val isPremium = billingState.isPremium

    val engineState by AutomationCoordinator.engineState.collectAsStateWithLifecycle()
    val isOverlayActive by AutomationCoordinator.isOverlayActive.collectAsStateWithLifecycle()
    val activeMacro by AutomationCoordinator.activeMacro.collectAsStateWithLifecycle()
    val lastPickedPixel by AutomationCoordinator.lastPickedPixel.collectAsStateWithLifecycle()
    val liveLogs by ExecutionLogBus.logs.collectAsStateWithLifecycle()
    val isCapturing by ScreenCaptureService.isCapturing.collectAsStateWithLifecycle()
    val isAccessibilityConnected by MacroAccessibilityService.isConnected.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var toolsSubTab by remember { mutableIntStateOf(0) } // 0=Lua, 1=Log, 2=Pengaturan
    var editingMacroId by remember { mutableStateOf<String?>(null) }
    var unlockedMacroIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var securitySheetMacroId by remember { mutableStateOf<String?>(null) }
    var autoScaleSheetMacroId by remember { mutableStateOf<String?>(null) }
    var logoSheetMacroId by remember { mutableStateOf<String?>(null) }
    var passwordPromptMacroId by remember { mutableStateOf<String?>(null) }
    var passwordPromptAction by remember { mutableStateOf("") } // "EDIT" | "SECURITY" | "LOGO" | "DUPLICATE"
    var pendingExportMacro by remember { mutableStateOf<Macro?>(null) }
    var pendingExportSingleUse by remember { mutableStateOf(false) }

    // Premium paywall & Play Store mandatory accessibility prominent disclosure state
    var paywallRequest by remember { mutableStateOf<Pair<PremiumFeature, String?>?>(null) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    fun openPaywall(feature: PremiumFeature, customReason: String? = null) {
        viewModel.clearBillingStatusMessage()
        paywallRequest = feature to customReason
    }

    fun requirePremium(feature: PremiumFeature, customReason: String? = null, action: () -> Unit) {
        if (isPremium) action() else openPaywall(feature, customReason)
    }

    fun openManageSubscriptions() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(BillingConfig.PLAY_SUBSCRIPTIONS_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("Buka Google Play → Langganan untuk mengelola paket Anda.") }
        }
    }

    // Macro Set modals & password-guard state
    var showCreateSetDialog by remember { mutableStateOf(false) }
    var createSetPreselectedMacroIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingSetId by remember { mutableStateOf<String?>(null) }
    var managingMembersSetId by remember { mutableStateOf<String?>(null) }
    var securitySheetSetId by remember { mutableStateOf<String?>(null) }
    var deletingSetId by remember { mutableStateOf<String?>(null) }
    var movingMacroId by remember { mutableStateOf<String?>(null) }
    var passwordPromptSetId by remember { mutableStateOf<String?>(null) }
    var passwordPromptSetAction by remember { mutableStateOf("") } // "EDIT_SET" | "MEMBERS" | "SECURITY" | "DUPLICATE" | "ADD_MACRO" | "MOVE_IN" | "MOVE_OUT"
    var pendingMoveMacroId by remember { mutableStateOf<String?>(null) }
    var pendingMoveTargetSetId by remember { mutableStateOf<String?>(null) }
    var pendingExportSet by remember { mutableStateOf<MacroSet?>(null) }
    var pendingExportSetSingleUse by remember { mutableStateOf(false) }

    var pendingExportImageName by remember { mutableStateOf<String?>(null) }
    var pendingExportImagesZip by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingImportGalleryFolder by remember { mutableStateOf("") }
    var pendingLogoMacro by remember { mutableStateOf<Macro?>(null) }
    var showLocalImportDialog by remember { mutableStateOf(false) }
    var localImportFiles by remember { mutableStateOf<List<com.example.data.CloudSafeStorage.LocalBackupEntry>>(emptyList()) }
    var loadingLocalImportFiles by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(context, result.resultCode, result.data!!)
            scope.launch { snackbarHostState.showSnackbar("Tangkap layar aktif & tetap menyala di latar belakang.") }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Izin tangkap layar dibatalkan.") }
        }
    }

    val exportMacroLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val macro = pendingExportMacro
        val singleUse = pendingExportSingleUse
        pendingExportMacro = null
        pendingExportSingleUse = false
        if (uri != null && macro != null) {
            viewModel.exportMacroToUri(macro, uri, singleUseImport = singleUse)
        }
    }

    val exportMacroSetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val set = pendingExportSet
        val singleUse = pendingExportSetSingleUse
        pendingExportSet = null
        pendingExportSetSingleUse = false
        if (uri != null && set != null) {
            viewModel.exportMacroSetToUri(set, uri, singleUseImport = singleUse)
        }
    }

    val exportImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val imgName = pendingExportImageName
        pendingExportImageName = null
        if (uri != null && imgName != null) {
            viewModel.exportImageToUri(imgName, uri)
        }
    }

    val exportImagesZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val list = pendingExportImagesZip
        pendingExportImagesZip = emptyList()
        if (uri != null && list.isNotEmpty()) {
            viewModel.exportImagesZipToUri(list, uri)
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.exportAllBackupToUri(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFromUri(uri)
    }

    val importGetContentFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.importFromUri(uri)
    }

    val galleryImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val folder = pendingImportGalleryFolder
        pendingImportGalleryFolder = ""
        if (uris.isNotEmpty()) viewModel.importImagesFromUris(uris, folder = folder)
    }

    val gallerySingleFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val folder = pendingImportGalleryFolder
        pendingImportGalleryFolder = ""
        if (uri != null) viewModel.importImagesFromUris(listOf(uri), folder = folder)
    }

    val macroLogoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val target = pendingLogoMacro
        pendingLogoMacro = null
        if (uri != null && target != null) {
            viewModel.setMacroLogoFromUri(target, uri)
        }
    }

    fun openLocalImportPicker() {
        requirePremium(PremiumFeature.EXPORT_IMPORT) {
            loadingLocalImportFiles = true
            showLocalImportDialog = true
            viewModel.loadLocalImportFiles { files ->
                localImportFiles = files
                loadingLocalImportFiles = false
            }
        }
    }

    fun safeExportMacro(m: Macro, singleUseImport: Boolean = false) {
        requirePremium(PremiumFeature.EXPORT_IMPORT) {
            val safeName = m.name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "macro" }
            val fileName = if (singleUseImport) "${safeName}_1x.zip" else "$safeName.zip"
            pendingExportMacro = m
            pendingExportSingleUse = singleUseImport
            runCatching {
                exportMacroLauncher.launch(fileName)
            }.onFailure {
                pendingExportMacro = null
                pendingExportSingleUse = false
                viewModel.exportMacroToDownloads(m, fileName, singleUseImport = singleUseImport)
            }
        }
    }

    fun safeExportMacroSet(set: MacroSet, singleUseImport: Boolean = false) {
        requirePremium(PremiumFeature.EXPORT_IMPORT) {
            val safeName = set.name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "macro_set" }
            val fileName = if (singleUseImport) "${safeName}_set_1x.zip" else "${safeName}_set.zip"
            pendingExportSet = set
            pendingExportSetSingleUse = singleUseImport
            runCatching {
                exportMacroSetLauncher.launch(fileName)
            }.onFailure {
                pendingExportSet = null
                pendingExportSetSingleUse = false
                viewModel.exportMacroSetToDownloads(set, fileName, singleUseImport = singleUseImport)
            }
        }
    }

    fun safeExportBackup() {
        requirePremium(PremiumFeature.EXPORT_IMPORT) {
            runCatching {
                exportBackupLauncher.launch("wjust_macro_backup.zip")
            }.onFailure {
                viewModel.exportAllBackupToDownloads()
            }
        }
    }

    fun safeExportImage(assetName: String, displayName: String) {
        requirePremium(PremiumFeature.EXPORT_IMPORT) {
            pendingExportImageName = assetName
            runCatching {
                exportImageLauncher.launch(displayName)
            }.onFailure {
                pendingExportImageName = null
                viewModel.exportImageToDownloads(assetName, displayName)
            }
        }
    }

    fun safeExportImagesZip(selected: List<String>) {
        requirePremium(PremiumFeature.EXPORT_IMPORT) {
            pendingExportImagesZip = selected
            runCatching {
                exportImagesZipLauncher.launch("gambar_wjust_macro.zip")
            }.onFailure {
                pendingExportImagesZip = emptyList()
                viewModel.exportImagesZipToDownloads(selected)
            }
        }
    }

    fun safeImportFile() {
        requirePremium(PremiumFeature.EXPORT_IMPORT) {
            val openedDoc = runCatching {
                importLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
            }.isSuccess
            if (openedDoc) return@requirePremium

            val openedContent = runCatching {
                importGetContentFallbackLauncher.launch("*/*")
            }.isSuccess
            if (!openedContent) {
                openLocalImportPicker()
            }
        }
    }

    fun safePickGalleryImages(folder: String) {
        pendingImportGalleryFolder = folder
        val okMulti = runCatching { galleryImageLauncher.launch("image/*") }.isSuccess
        if (okMulti) return
        val okSingle = runCatching { gallerySingleFallbackLauncher.launch("image/*") }.isSuccess
        if (!okSingle) {
            pendingImportGalleryFolder = ""
            scope.launch { snackbarHostState.showSnackbar("Pemilih galeri tidak tersedia di perangkat ini.") }
        }
    }

    fun safePickMacroLogo(target: Macro) {
        requirePremium(PremiumFeature.CUSTOM_LOGO) {
            pendingLogoMacro = target
            runCatching {
                macroLogoLauncher.launch("image/*")
            }.onFailure {
                pendingLogoMacro = null
                scope.launch { snackbarHostState.showSnackbar("Pemilih gambar sistem tidak tersedia — pilih dari Pustaka Gambar.") }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions(context)
                viewModel.refreshBilling()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isCapturing, isAccessibilityConnected) {
        viewModel.refreshPermissions(context)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("Buka Pengaturan → Aksesibilitas secara manual.") }
        }
    }

    fun requestAccessibilityWithDisclosure() {
        showAccessibilityDisclosure = true
    }

    fun requestOverlayPermission() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("Aktifkan izin Overlay di Pengaturan Aplikasi secara manual.") }
            }
        }
    }

    fun requestScreenCapture() {
        runCatching {
            val mgr = context.getSystemService(MediaProjectionManager::class.java)
            mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("Gagal membuka izin Tangkap Layar: ${it.message}") }
        }
    }

    fun requestBatteryUnrestricted() {
        if (permissions.batteryOptimizationIgnored) {
            scope.launch { snackbarHostState.showSnackbar("Mode Tetap Aktif (bebas optimasi baterai) sudah menyala.") }
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("Pengaturan optimasi baterai tidak tersedia di perangkat ini.") }
            }
        }
    }

    fun launchOverlay(
        action: String = FloatingOverlayService.ACTION_START,
        addToMacro: Boolean = true,
        targetActionId: String? = null,
        targetFolder: String = ""
    ) {
        if (!Settings.canDrawOverlays(context)) {
            scope.launch { snackbarHostState.showSnackbar("Aktifkan izin 'Tampilkan di atas aplikasi lain' terlebih dahulu.") }
            requestOverlayPermission()
            return
        }
        FloatingOverlayService.start(context, action, addToMacro, targetActionId, targetFolder)
    }

    fun screenMetrics(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context as? Activity)?.windowManager?.defaultDisplay?.getRealMetrics(metrics)
        return (metrics.widthPixels.takeIf { it > 0 } ?: 1080) to (metrics.heightPixels.takeIf { it > 0 } ?: 2400)
    }

    fun parentSetOf(m: Macro): MacroSet? =
        if (m.setId.isBlank()) null else macroSets.firstOrNull { it.id == m.setId }

    fun requestEditMacro(m: Macro) {
        if (MacroSecurity.isEffectivelyLocked(m, parentSetOf(m)) && m.id !in unlockedMacroIds) {
            passwordPromptMacroId = m.id
            passwordPromptAction = "EDIT"
        } else {
            editingMacroId = m.id
        }
    }

    fun requestOpenSecurity(m: Macro) {
        requirePremium(PremiumFeature.SECURITY_LOCK) {
            val pSet = parentSetOf(m)
            if (MacroSecurity.hasPassword(m) || (pSet != null && MacroSecurity.hasSetPassword(pSet))) {
                passwordPromptMacroId = m.id
                passwordPromptAction = "SECURITY"
            } else {
                securitySheetMacroId = m.id
            }
        }
    }

    fun requestChangeLogo(m: Macro) {
        requirePremium(PremiumFeature.CUSTOM_LOGO) {
            if (MacroSecurity.isEffectivelyLocked(m, parentSetOf(m)) && m.id !in unlockedMacroIds) {
                passwordPromptMacroId = m.id
                passwordPromptAction = "LOGO"
            } else {
                logoSheetMacroId = m.id
            }
        }
    }

    fun requestDuplicateMacro(m: Macro) {
        if (!PremiumFeatureGate.canCreateMacro(macros.size, isPremium)) {
            openPaywall(PremiumFeature.MACRO_LIMIT)
            return
        }
        if (MacroSecurity.isEffectivelyLocked(m, parentSetOf(m)) && m.id !in unlockedMacroIds) {
            passwordPromptMacroId = m.id
            passwordPromptAction = "DUPLICATE"
        } else {
            viewModel.duplicateMacro(m)
        }
    }

    fun requestCreateMacroInSet(set: MacroSet) {
        requirePremium(PremiumFeature.MACRO_SET) {
            if (set.isLocked) {
                passwordPromptSetId = set.id
                passwordPromptSetAction = "ADD_MACRO"
            } else {
                val created = viewModel.createNewMacro(setId = set.id)
                editingMacroId = created.id
            }
        }
    }

    fun requestEditMacroSet(set: MacroSet) {
        requirePremium(PremiumFeature.MACRO_SET) {
            if (set.isLocked) {
                passwordPromptSetId = set.id
                passwordPromptSetAction = "EDIT_SET"
            } else {
                editingSetId = set.id
            }
        }
    }

    fun requestManageMacroSetMembers(set: MacroSet) {
        requirePremium(PremiumFeature.MACRO_SET) {
            if (set.isLocked) {
                passwordPromptSetId = set.id
                passwordPromptSetAction = "MEMBERS"
            } else {
                managingMembersSetId = set.id
            }
        }
    }

    fun requestOpenMacroSetSecurity(set: MacroSet) {
        requirePremium(PremiumFeature.MACRO_SET) {
            if (MacroSecurity.hasSetPassword(set)) {
                passwordPromptSetId = set.id
                passwordPromptSetAction = "SECURITY"
            } else {
                securitySheetSetId = set.id
            }
        }
    }

    fun requestDuplicateMacroSet(set: MacroSet) {
        requirePremium(PremiumFeature.MACRO_SET) {
            if (set.isLocked) {
                passwordPromptSetId = set.id
                passwordPromptSetAction = "DUPLICATE"
            } else {
                viewModel.duplicateMacroSet(set)
            }
        }
    }

    fun requestMoveMacroToSet(m: Macro) {
        requirePremium(PremiumFeature.MACRO_SET) {
            val curSet = parentSetOf(m)
            if (curSet != null && curSet.isLocked) {
                pendingMoveMacroId = m.id
                passwordPromptSetId = curSet.id
                passwordPromptSetAction = "MOVE_OUT"
            } else {
                movingMacroId = m.id
            }
        }
    }

    fun requestTogglePassivePull(m: Macro) {
        requirePremium(PremiumFeature.SECURITY_LOCK) {
            if (!m.isPassive) return@requirePremium
            viewModel.toggleMacroPassiveEnabled(m)
        }
    }

    fun launchMacroIfAllowed(m: Macro) {
        val pSet = parentSetOf(m)
        val denial = MacroSecurity.checkDirectRun(m, viewModel.effectiveNowMs(), pSet)
        if (denial != null) {
            scope.launch { snackbarHostState.showSnackbar(MacroSecurity.denialMessage(m, denial, pSet)) }
            return
        }
        val premiumDenial = PremiumFeatureGate.checkMacroRun(m, isPremium)
        if (premiumDenial != null) {
            openPaywall(premiumDenial.feature, premiumDenial.reason)
            return
        }
        AutomationCoordinator.selectMacro(m)
        launchOverlay()
    }

    val editingMacro = editingMacroId?.let { id -> macros.firstOrNull { it.id == id } }
    if (editingMacroId != null && editingMacro == null) {
        editingMacroId = null
    }

    BackHandler(enabled = editingMacro != null) {
        editingMacroId?.let { unlockedMacroIds = unlockedMacroIds - it }
        editingMacroId = null
    }

    TapLuaTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        if (editingMacro != null) {
            val (sw, sh) = screenMetrics()
            MacroEditorScreen(
                macro = editingMacro,
                allMacros = macros,
                images = images,
                imageFolders = imageFolders,
                lastPickedPixel = lastPickedPixel,
                logoPath = if (editingMacro.logoImage.isNotBlank()) viewModel.macroIconPath(editingMacro.logoImage) else null,
                screenW = sw,
                screenH = sh,
                snackbarHostState = snackbarHostState,
                onUpdateMacro = { updated ->
                    val toSave = if (
                        (updated.actions != editingMacro.actions || updated.stuckActions != editingMacro.stuckActions) &&
                        (updated.sourceWidth <= 0 || updated.sourceHeight <= 0) &&
                        com.example.data.ResolutionScaler.hasCoordinateActions(updated)
                    ) {
                        val (iw, ih) = com.example.data.ResolutionScaler.inferSourceSizeForDevice(updated, sw, sh)
                        updated.copy(sourceWidth = iw, sourceHeight = ih)
                    } else {
                        updated
                    }
                    viewModel.saveMacro(toSave)
                },
                onRecropImage = viewModel::recropImage,
                onChangeLogo = { requestChangeLogo(editingMacro) },
                onOpenSecurity = { requestOpenSecurity(editingMacro) },
                onTogglePassivePull = { requestTogglePassivePull(editingMacro) },
                onLaunchOverlay = ::launchMacroIfAllowed,
                onLaunchCropOverlay = { m, targetActionId ->
                    AutomationCoordinator.selectMacro(m)
                    launchOverlay(
                        action = FloatingOverlayService.ACTION_OPEN_CROP,
                        addToMacro = targetActionId == null,
                        targetActionId = targetActionId
                    )
                },
                onLaunchTargetPickerOverlay = { m, targetActionId ->
                    AutomationCoordinator.selectMacro(m)
                    if (Settings.canDrawOverlays(context)) {
                        (context as? Activity)?.moveTaskToBack(true)
                    }
                    launchOverlay(
                        action = FloatingOverlayService.ACTION_PICK_TARGET,
                        addToMacro = false,
                        targetActionId = targetActionId
                    )
                },
                onLaunchRegionPickerOverlay = { m, targetActionId ->
                    AutomationCoordinator.selectMacro(m)
                    if (Settings.canDrawOverlays(context)) {
                        (context as? Activity)?.moveTaskToBack(true)
                    }
                    launchOverlay(
                        action = FloatingOverlayService.ACTION_PICK_REGION,
                        addToMacro = false,
                        targetActionId = targetActionId
                    )
                },
                onLaunchRecorderOverlay = { m ->
                    requirePremium(PremiumFeature.TOUCH_RECORDER) {
                        AutomationCoordinator.selectMacro(m)
                        launchOverlay(FloatingOverlayService.ACTION_OPEN_RECORDER)
                    }
                },
                onBack = {
                    unlockedMacroIds = unlockedMacroIds - editingMacro.id
                    editingMacroId = null
                },
                isPremium = isPremium,
                onRequirePremium = { feature, reason -> openPaywall(feature, reason) }
            )
        } else {
            val (homeSw, homeSh) = screenMetrics()
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars,
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                        MainTab.entries.forEachIndexed { idx, tab ->
                            val selected = selectedTab == idx
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedTab = idx },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Crossfade(
                    targetState = MainTab.entries[selectedTab],
                    animationSpec = tween(durationMillis = 180),
                    label = "mainTabCrossfade",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) { activeTab ->
                    when (activeTab) {
                        MainTab.MACROS -> MacroHomeScreen(
                            macros = macros,
                            macroSets = macroSets,
                            permissions = permissions,
                            engineState = engineState,
                            isOverlayActive = isOverlayActive,
                            activeMacroId = activeMacro.id,
                            nowMs = viewModel.effectiveNowMs(),
                            screenW = homeSw,
                            screenH = homeSh,
                            resolveLogoPath = viewModel::macroIconPath,
                            onOpenAccessibility = {
                                if (permissions.accessibilityEnabled) {
                                    scope.launch { snackbarHostState.showSnackbar("Aksesibilitas sudah aktif.") }
                                } else {
                                    requestAccessibilityWithDisclosure()
                                }
                            },
                            onRequestOverlay = {
                                if (permissions.overlayEnabled) {
                                    scope.launch { snackbarHostState.showSnackbar("Izin Overlay sudah aktif.") }
                                } else {
                                    requestOverlayPermission()
                                }
                            },
                            onActivateScreenCapture = {
                                if (permissions.screenCaptureActive) {
                                    scope.launch { snackbarHostState.showSnackbar("Tangkap layar sudah aktif & tetap menyala.") }
                                } else {
                                    requestScreenCapture()
                                }
                            },
                            onRequestBatteryUnrestricted = ::requestBatteryUnrestricted,
                            onToggleOverlay = {
                                if (isOverlayActive) FloatingOverlayService.stop(context)
                                else launchOverlay()
                            },
                            onStopEngine = { AutomationCoordinator.stop() },
                            onQuickAutoClicker = {
                                if (!PremiumFeatureGate.canCreateMacro(macros.size, isPremium)) {
                                    openPaywall(PremiumFeature.MACRO_LIMIT)
                                } else {
                                    val (sw, sh) = screenMetrics()
                                    val quick = viewModel.createQuickAutoClicker(sw, sh)
                                    AutomationCoordinator.selectMacro(quick)
                                    launchOverlay()
                                }
                            },
                            onCreateMacro = {
                                if (!PremiumFeatureGate.canCreateMacro(macros.size, isPremium)) {
                                    openPaywall(PremiumFeature.MACRO_LIMIT)
                                } else {
                                    val created = viewModel.createNewMacro()
                                    editingMacroId = created.id
                                }
                            },
                            onCreateMacroInSet = ::requestCreateMacroInSet,
                            onImportMacro = ::safeImportFile,
                            onCreateMacroSet = {
                                requirePremium(PremiumFeature.MACRO_SET) {
                                    createSetPreselectedMacroIds = emptySet()
                                    showCreateSetDialog = true
                                }
                            },
                            onEditMacroSet = ::requestEditMacroSet,
                            onManageMacroSetMembers = ::requestManageMacroSetMembers,
                            onOpenMacroSetSecurity = ::requestOpenMacroSetSecurity,
                            onDuplicateMacroSet = ::requestDuplicateMacroSet,
                            onExportMacroSet = { set, singleUse -> safeExportMacroSet(set, singleUseImport = singleUse) },
                            onExportMacroSetDirectToDownloads = { set, singleUse ->
                                requirePremium(PremiumFeature.EXPORT_IMPORT) {
                                    val safeName = set.name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "macro_set" }
                                    val fileName = if (singleUse) "${safeName}_set_1x.zip" else "${safeName}_set.zip"
                                    viewModel.exportMacroSetToDownloads(set, fileName, singleUseImport = singleUse)
                                }
                            },
                            onDeleteMacroSet = { set -> deletingSetId = set.id },
                            onMoveMacroToSet = ::requestMoveMacroToSet,
                            onEditMacro = ::requestEditMacro,
                            onLaunchMacroOverlay = ::launchMacroIfAllowed,
                            onChangeMacroLogo = ::requestChangeLogo,
                            onOpenMacroSecurity = ::requestOpenSecurity,
                            onOpenAutoScale = { m ->
                                requirePremium(PremiumFeature.AUTO_SCALE) {
                                    autoScaleSheetMacroId = m.id
                                }
                            },
                            onTogglePassivePull = ::requestTogglePassivePull,
                            onDuplicateMacro = ::requestDuplicateMacro,
                            onExportMacro = { m, singleUse -> safeExportMacro(m, singleUseImport = singleUse) },
                            onExportMacroDirectToDownloads = { m, singleUse ->
                                requirePremium(PremiumFeature.EXPORT_IMPORT) {
                                    val safeName = m.name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "macro" }
                                    val fileName = if (singleUse) "${safeName}_1x.zip" else "$safeName.zip"
                                    viewModel.exportMacroToDownloads(m, fileName, singleUseImport = singleUse)
                                }
                            },
                            onDeleteMacro = { m -> viewModel.deleteMacro(m.id) },
                            isPremium = isPremium,
                            activePlanLabel = billingState.activePlanLabel,
                            onOpenPaywall = { f -> openPaywall(f) }
                        )

                        MainTab.IMAGES -> ImageLibraryScreen(
                            images = images,
                            folders = imageFolders,
                            onCropFromScreen = { folder ->
                                launchOverlay(
                                    action = FloatingOverlayService.ACTION_OPEN_CROP,
                                    addToMacro = false,
                                    targetFolder = folder
                                )
                            },
                            onImportFromGallery = ::safePickGalleryImages,
                            onCreateFolder = viewModel::createImageFolder,
                            onDeleteFolder = viewModel::deleteImageFolder,
                            onMoveImages = viewModel::moveImages,
                            onRecropImage = viewModel::recropImage,
                            onExportImage = { asset ->
                                safeExportImage(asset.name, asset.displayName)
                            },
                            onExportMultipleImages = { selected ->
                                safeExportImagesZip(selected.toList())
                            },
                            onRenameImage = viewModel::renameImage,
                            onDeleteImage = viewModel::deleteImage,
                            onDeleteMultipleImages = viewModel::deleteImages
                        )

                        MainTab.TOOLS -> Column(modifier = Modifier.fillMaxSize()) {
                            val options = listOf(
                                if (isPremium) "Lua Studio" else "🔒 Lua",
                                "Live Log",
                                "Pengaturan"
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                options.forEachIndexed { i, label ->
                                    SegmentedButton(
                                        selected = toolsSubTab == i,
                                        onClick = { toolsSubTab = i },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size)
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                            when (toolsSubTab) {
                                0 -> if (isPremium) {
                                    LuaStudioScreen(
                                        scripts = scripts,
                                        images = images,
                                        syntaxResult = syntaxResult,
                                        onCreateScript = viewModel::createNewScript,
                                        onSaveScript = viewModel::saveScript,
                                        onDeleteScript = viewModel::deleteScript,
                                        onValidateSyntax = viewModel::validateLuaSyntax,
                                        onClearSyntaxResult = viewModel::clearSyntaxResult,
                                        onLaunchOverlayWithScript = { s ->
                                            AutomationCoordinator.selectLuaScript(s)
                                            launchOverlay()
                                        },
                                        onRunScriptNow = { s ->
                                            if (!permissions.accessibilityEnabled) {
                                                scope.launch { snackbarHostState.showSnackbar("Aktifkan Wjust macro di Accessibility Settings terlebih dahulu.") }
                                                requestAccessibilityWithDisclosure()
                                            } else {
                                                val (sw, sh) = screenMetrics()
                                                AutomationCoordinator.runLuaScript(s, sw, sh)
                                            }
                                        }
                                    )
                                } else {
                                    LuaStudioLockedPlaceholder(
                                        onUpgrade = { openPaywall(PremiumFeature.LUA_STUDIO) }
                                    )
                                }
                                1 -> LiveLogPanel(logs = liveLogs)
                                2 -> SettingsPanel(
                                    settings = settings,
                                    onUpdateSettings = viewModel::updateSettings,
                                    onExportAllBackup = ::safeExportBackup,
                                    onImportBackup = ::safeImportFile,
                                    onExportBackupDirectToDownloads = {
                                        requirePremium(PremiumFeature.EXPORT_IMPORT) {
                                            viewModel.exportAllBackupToDownloads()
                                        }
                                    },
                                    onImportFromDownloadsFolder = ::openLocalImportPicker,
                                    billingState = billingState,
                                    onOpenPaywall = { openPaywall(PremiumFeature.UPGRADE_GENERAL) },
                                    onRestorePurchases = viewModel::restorePurchases,
                                    onManageSubscriptions = ::openManageSubscriptions
                                )
                            }
                        }
                    }
                }
            }
        }

        // Password verification modal (guards locked edit, security/Active-Passive mode changes, logo change, duplicate)
        val promptMacro = passwordPromptMacroId?.let { id -> macros.firstOrNull { it.id == id } }
        if (promptMacro != null) {
            val pSet = parentSetOf(promptMacro)
            val subtitle = when (passwordPromptAction) {
                "SECURITY" -> "Masukkan sandi '${promptMacro.name}' untuk mengubah Kunci, Tenggat Waktu, atau status Aktif/Pasif."
                "LOGO" -> "Masukkan sandi '${promptMacro.name}' untuk mengubah foto logo."
                "DUPLICATE" -> "Masukkan sandi '${promptMacro.name}' untuk menduplikat macro terkunci."
                else -> "Macro '${promptMacro.name}' dikunci. Masukkan sandi untuk membuka editor."
            }
            PasswordUnlockDialog(
                title = "Sandi Macro Diperlukan",
                subtitle = subtitle,
                onVerify = { candidate -> MacroSecurity.verifyMacroOrSetPassword(promptMacro, pSet, candidate) },
                onUnlocked = {
                    val actionType = passwordPromptAction
                    val target = promptMacro
                    passwordPromptMacroId = null
                    passwordPromptAction = ""
                    when (actionType) {
                        "EDIT" -> {
                            unlockedMacroIds = unlockedMacroIds + target.id
                            editingMacroId = target.id
                        }
                        "SECURITY" -> {
                            securitySheetMacroId = target.id
                        }
                        "LOGO" -> {
                            unlockedMacroIds = unlockedMacroIds + target.id
                            logoSheetMacroId = target.id
                        }
                        "DUPLICATE" -> {
                            viewModel.duplicateMacro(target)
                        }
                    }
                },
                onDismiss = {
                    passwordPromptMacroId = null
                    passwordPromptAction = ""
                }
            )
        }

        // Password verification modal for locked Macro Set actions
        val promptSet = passwordPromptSetId?.let { id -> macroSets.firstOrNull { it.id == id } }
        if (promptSet != null) {
            val subtitle = when (passwordPromptSetAction) {
                "SECURITY" -> "Masukkan sandi Macro Set '${promptSet.name}' untuk mengubah Kunci atau Tenggat Waktu Set."
                "MEMBERS" -> "Masukkan sandi Macro Set '${promptSet.name}' untuk menambah/mengeluarkan macro."
                "EDIT_SET" -> "Masukkan sandi Macro Set '${promptSet.name}' untuk mengubah nama & warna Set."
                "DUPLICATE" -> "Masukkan sandi Macro Set '${promptSet.name}' untuk menduplikat Set terkunci."
                "ADD_MACRO" -> "Masukkan sandi Macro Set '${promptSet.name}' untuk menambah macro baru ke dalam Set."
                "MOVE_IN" -> "Macro Set '${promptSet.name}' terkunci. Masukkan sandi Set untuk memasukkan macro ini."
                "MOVE_OUT" -> "Macro berada di Set terkunci '${promptSet.name}'. Masukkan sandi Set untuk memindahkannya."
                else -> "Masukkan sandi Macro Set '${promptSet.name}'."
            }
            PasswordUnlockDialog(
                title = "Sandi Macro Set Diperlukan",
                subtitle = subtitle,
                onVerify = { candidate -> MacroSecurity.verifySetPassword(promptSet, candidate) },
                onUnlocked = {
                    val actionType = passwordPromptSetAction
                    val targetSet = promptSet
                    val moveMId = pendingMoveMacroId
                    val moveDestId = pendingMoveTargetSetId
                    passwordPromptSetId = null
                    passwordPromptSetAction = ""
                    pendingMoveMacroId = null
                    pendingMoveTargetSetId = null
                    when (actionType) {
                        "SECURITY" -> securitySheetSetId = targetSet.id
                        "MEMBERS" -> managingMembersSetId = targetSet.id
                        "EDIT_SET" -> editingSetId = targetSet.id
                        "DUPLICATE" -> viewModel.duplicateMacroSet(targetSet)
                        "ADD_MACRO" -> {
                            val created = viewModel.createNewMacro(setId = targetSet.id)
                            unlockedMacroIds = unlockedMacroIds + created.id
                            editingMacroId = created.id
                        }
                        "MOVE_OUT" -> {
                            if (moveMId != null) movingMacroId = moveMId
                        }
                        "MOVE_IN" -> {
                            val m = moveMId?.let { id -> macros.firstOrNull { it.id == id } }
                            if (m != null && moveDestId != null) {
                                viewModel.moveMacroToSet(m, moveDestId)
                            }
                        }
                    }
                },
                onDismiss = {
                    passwordPromptSetId = null
                    passwordPromptSetAction = ""
                    pendingMoveMacroId = null
                    pendingMoveTargetSetId = null
                }
            )
        }

        // Create New Macro Set Dialog
        if (showCreateSetDialog) {
            CreateOrEditMacroSetDialog(
                existingSet = null,
                allMacros = macros,
                allSets = macroSets,
                preselectedMacroIds = createSetPreselectedMacroIds,
                onConfirm = { name, desc, colorHex, selectedIds ->
                    viewModel.createMacroSet(name, desc, colorHex, selectedIds)
                    showCreateSetDialog = false
                    createSetPreselectedMacroIds = emptySet()
                },
                onDismiss = {
                    showCreateSetDialog = false
                    createSetPreselectedMacroIds = emptySet()
                }
            )
        }

        // Edit Existing Macro Set Info Dialog
        val editSetTarget = editingSetId?.let { id -> macroSets.firstOrNull { it.id == id } }
        if (editSetTarget != null) {
            CreateOrEditMacroSetDialog(
                existingSet = editSetTarget,
                onConfirm = { name, desc, colorHex, _ ->
                    viewModel.updateMacroSetInfo(editSetTarget, name, desc, colorHex)
                    editingSetId = null
                },
                onDismiss = { editingSetId = null }
            )
        }

        // Manage Macro Set Members Sheet
        val manageSetTarget = managingMembersSetId?.let { id -> macroSets.firstOrNull { it.id == id } }
        if (manageSetTarget != null) {
            ManageMacroSetMembersSheet(
                set = manageSetTarget,
                allMacros = macros,
                allSets = macroSets,
                onSave = { selectedIds ->
                    viewModel.setMacrosInSet(manageSetTarget, selectedIds)
                    managingMembersSetId = null
                },
                onDismiss = { managingMembersSetId = null }
            )
        }

        // Macro Set Security & Lock Sheet
        val secSetTarget = securitySheetSetId?.let { id -> macroSets.firstOrNull { it.id == id } }
        if (secSetTarget != null) {
            MacroSetSecuritySheet(
                set = secSetTarget,
                memberCount = macros.count { it.setId == secSetTarget.id },
                nowMs = viewModel.effectiveNowMs(),
                onSave = { updated ->
                    viewModel.saveMacroSetSecurity(updated)
                    securitySheetSetId = null
                },
                onDismiss = { securitySheetSetId = null }
            )
        }

        // Move Macro to Set Dialog
        val moveMacroTarget = movingMacroId?.let { id -> macros.firstOrNull { it.id == id } }
        if (moveMacroTarget != null) {
            val counts = remember(macros) { macros.groupingBy { it.setId }.eachCount() }
            MoveMacroToSetDialog(
                macro = moveMacroTarget,
                macroSets = macroSets,
                memberCounts = counts,
                onSelectSet = { targetSetId ->
                    movingMacroId = null
                    val destSet = if (targetSetId.isBlank()) null else macroSets.firstOrNull { it.id == targetSetId }
                    if (destSet != null && destSet.isLocked && moveMacroTarget.setId != destSet.id) {
                        pendingMoveMacroId = moveMacroTarget.id
                        pendingMoveTargetSetId = destSet.id
                        passwordPromptSetId = destSet.id
                        passwordPromptSetAction = "MOVE_IN"
                    } else {
                        viewModel.moveMacroToSet(moveMacroTarget, targetSetId)
                    }
                },
                onCreateNewSetWithMacro = {
                    movingMacroId = null
                    createSetPreselectedMacroIds = setOf(moveMacroTarget.id)
                    showCreateSetDialog = true
                },
                onDismiss = { movingMacroId = null }
            )
        }

        // Delete Macro Set Confirmation Dialog
        val delSetTarget = deletingSetId?.let { id -> macroSets.firstOrNull { it.id == id } }
        if (delSetTarget != null) {
            DeleteMacroSetDialog(
                set = delSetTarget,
                memberCount = macros.count { it.setId == delSetTarget.id },
                onDeleteSetOnly = {
                    viewModel.deleteMacroSet(delSetTarget, deleteMembers = false)
                    deletingSetId = null
                },
                onDeleteSetAndMembers = {
                    viewModel.deleteMacroSet(delSetTarget, deleteMembers = true)
                    deletingSetId = null
                },
                onDismiss = { deletingSetId = null }
            )
        }

        // Macro Security Sheet (Lock + Days/Months Expiry + Passive Mode ON/OFF + Password)
        val secMacro = securitySheetMacroId?.let { id -> macros.firstOrNull { it.id == id } }
        if (secMacro != null) {
            MacroSecuritySheet(
                macro = secMacro,
                nowMs = viewModel.effectiveNowMs(),
                onSave = { updated ->
                    viewModel.saveMacro(updated)
                    securitySheetMacroId = null
                    val summary = buildList {
                        if (updated.isLocked) add("Terkunci")
                        if (updated.isPassive) add(if (updated.passiveEnabled) "Pasif ON" else "Pasif OFF")
                        if (updated.expiresAtEpochMs > 0L) add("Tenggat ${updated.expiryLabel}")
                    }.joinToString(", ")
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (summary.isBlank()) "Semua proteksi '${updated.name}' dimatikan."
                            else "Proteksi '${updated.name}' disimpan: $summary."
                        )
                    }
                },
                onDismiss = { securitySheetMacroId = null }
            )
        }

        // Passwordless Auto-Scale Settings Sheet (always accessible even when macro is locked)
        val scaleMacro = autoScaleSheetMacroId?.let { id -> macros.firstOrNull { it.id == id } }
        if (scaleMacro != null) {
            val (scaleSw, scaleSh) = screenMetrics()
            com.example.ui.AutoScaleSettingsSheet(
                macro = scaleMacro,
                screenW = scaleSw,
                screenH = scaleSh,
                onSave = { enabled, srcW, srcH, mode ->
                    viewModel.updateMacroAutoScale(scaleMacro, enabled, srcW, srcH, mode)
                    autoScaleSheetMacroId = null
                },
                onDismiss = { autoScaleSheetMacroId = null }
            )
        }

        // Custom Macro Logo Picker Sheet
        val logoMacro = logoSheetMacroId?.let { id -> macros.firstOrNull { it.id == id } }
        if (logoMacro != null) {
            MacroLogoPickerSheet(
                macro = logoMacro,
                logoFilePath = if (logoMacro.logoImage.isNotBlank()) viewModel.macroIconPath(logoMacro.logoImage) else null,
                images = images,
                onPickFromGallery = {
                    logoSheetMacroId = null
                    safePickMacroLogo(logoMacro)
                },
                onPickFromLibrary = { asset ->
                    viewModel.setMacroLogoFromLibrary(logoMacro, asset.name)
                    logoSheetMacroId = null
                },
                onRemoveLogo = {
                    viewModel.removeMacroLogo(logoMacro)
                    logoSheetMacroId = null
                },
                onDismiss = { logoSheetMacroId = null }
            )
        }

        // CloudPhone / Redfinger Local Download Folder Import Dialog
        if (showLocalImportDialog) {
            LocalImportPickerDialog(
                files = localImportFiles,
                loading = loadingLocalImportFiles,
                onRefresh = { openLocalImportPicker() },
                onPickFile = { entry ->
                    showLocalImportDialog = false
                    viewModel.importFromLocalBackup(entry)
                },
                onDismiss = { showLocalImportDialog = false }
            )
        }

        // Play Store Mandatory Prominent Disclosure Dialog before opening Accessibility Settings
        if (showAccessibilityDisclosure) {
            AccessibilityDisclosureDialog(
                onAccept = {
                    showAccessibilityDisclosure = false
                    openAccessibilitySettings()
                },
                onDecline = {
                    showAccessibilityDisclosure = false
                }
            )
        }

        // Wjust macro PRO Subscription Paywall Sheet (Monthly & Yearly via Google Play Billing)
        paywallRequest?.let { (feature, customReason) ->
            PremiumPaywallSheet(
                feature = feature,
                customReason = customReason,
                billingState = billingState,
                onSelectSubscribe = { plan ->
                    val activity = context as? Activity
                    if (activity != null) {
                        viewModel.launchSubscription(activity, plan)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("Tidak dapat membuka dialog Google Play.") }
                    }
                },
                onRestorePurchases = { viewModel.restorePurchases() },
                onManageSubscriptions = ::openManageSubscriptions,
                onDebugTogglePremium = { override -> viewModel.setDebugPremiumOverride(override) },
                onDismiss = { paywallRequest = null }
            )
        }
    }
}

@Composable
private fun LuaStudioLockedPlaceholder(
    onUpgrade: () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.material3.Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                ProLockBadge(text = "FITUR PRO")
                Text(
                    text = "Lua Studio Khusus Wjust macro PRO",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = "Tulis, validasi, dan jalankan script otomasi Lua lengkap (image detection, OCR, loop, dan fungsi kustom) dengan berlangganan Wjust macro PRO.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                androidx.compose.material3.Button(
                    onClick = onUpgrade,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Upgrade ke Wjust macro PRO")
                }
            }
        }
    }
}

@Composable
private fun LocalImportPickerDialog(
    files: List<com.example.data.CloudSafeStorage.LocalBackupEntry>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onPickFile: (com.example.data.CloudSafeStorage.LocalBackupEntry) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Impor dari Folder Download",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Menampilkan file .zip / .json di Download/WjustMacro/ dan Download/ (cocok untuk CloudPhone/Redfinger).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                when {
                    loading -> {
                        Text("Memindai folder Download...", style = MaterialTheme.typography.bodyMedium)
                    }
                    files.isEmpty() -> {
                        Text(
                            "Belum ada file .zip atau .json ditemukan di folder Download/WjustMacro/ atau Download/.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                        ) {
                            items(files.size) { index ->
                                val item = files[index]
                                val kb = ((item.sizeBytes + 1023L) / 1024L).coerceAtLeast(1L)
                                androidx.compose.material3.Surface(
                                    onClick = { onPickFile(item) },
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text(
                                            item.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1
                                        )
                                        Text(
                                            "${item.displayPath} • ${kb} KB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onRefresh) {
                Text("Segarkan")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}

