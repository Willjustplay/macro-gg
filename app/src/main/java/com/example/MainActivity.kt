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
import androidx.compose.foundation.layout.Box
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
import com.example.data.Macro
import com.example.ui.ImageLibraryScreen
import com.example.ui.LiveLogPanel
import com.example.ui.LuaStudioScreen
import com.example.ui.MacroEditorScreen
import com.example.ui.MacroHomeScreen
import com.example.ui.MacroViewModel
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
            MacroGGApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacroGGApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: MacroViewModel = viewModel(factory = MacroViewModel.Factory(context))

    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val scripts by viewModel.scripts.collectAsStateWithLifecycle()
    val images by viewModel.images.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val syntaxResult by viewModel.syntaxResult.collectAsStateWithLifecycle()

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
    var pendingExportMacro by remember { mutableStateOf<Macro?>(null) }

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
        pendingExportMacro = null
        if (uri != null && macro != null) {
            viewModel.exportMacroToUri(macro, uri)
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

    val galleryImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.importImageFromUri(uri, "gambar")
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions(context)
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
            if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    fun openAccessibilitySettings() {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    fun requestOverlayPermission() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    fun requestScreenCapture() {
        val mgr = context.getSystemService(MediaProjectionManager::class.java)
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
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
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun launchOverlay(
        action: String = FloatingOverlayService.ACTION_START,
        addToMacro: Boolean = true,
        targetActionId: String? = null
    ) {
        if (!Settings.canDrawOverlays(context)) {
            scope.launch { snackbarHostState.showSnackbar("Aktifkan izin 'Tampilkan di atas aplikasi lain' terlebih dahulu.") }
            requestOverlayPermission()
            return
        }
        FloatingOverlayService.start(context, action, addToMacro, targetActionId)
    }

    fun screenMetrics(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context as? Activity)?.windowManager?.defaultDisplay?.getRealMetrics(metrics)
        return (metrics.widthPixels.takeIf { it > 0 } ?: 1080) to (metrics.heightPixels.takeIf { it > 0 } ?: 2400)
    }

    val editingMacro = editingMacroId?.let { id -> macros.firstOrNull { it.id == id } }
    if (editingMacroId != null && editingMacro == null) {
        editingMacroId = null
    }

    BackHandler(enabled = editingMacro != null) {
        editingMacroId = null
    }

    TapLuaTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        if (editingMacro != null) {
            val (sw, sh) = screenMetrics()
            MacroEditorScreen(
                macro = editingMacro,
                allMacros = macros,
                images = images,
                lastPickedPixel = lastPickedPixel,
                screenW = sw,
                screenH = sh,
                onUpdateMacro = { updated -> viewModel.saveMacro(updated) },
                onRecropImage = viewModel::recropImage,
                onLaunchOverlay = { m ->
                    AutomationCoordinator.selectMacro(m)
                    launchOverlay()
                },
                onLaunchCropOverlay = { m, targetActionId ->
                    AutomationCoordinator.selectMacro(m)
                    launchOverlay(
                        action = FloatingOverlayService.ACTION_OPEN_CROP,
                        addToMacro = targetActionId == null,
                        targetActionId = targetActionId
                    )
                },
                onLaunchRecorderOverlay = { m ->
                    AutomationCoordinator.selectMacro(m)
                    launchOverlay(FloatingOverlayService.ACTION_OPEN_RECORDER)
                },
                onBack = { editingMacroId = null }
            )
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    NavigationBar(windowInsets = WindowInsets.navigationBars) {
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (MainTab.entries[selectedTab]) {
                        MainTab.MACROS -> MacroHomeScreen(
                            macros = macros,
                            permissions = permissions,
                            engineState = engineState,
                            isOverlayActive = isOverlayActive,
                            activeMacroId = activeMacro.id,
                            onOpenAccessibility = {
                                if (permissions.accessibilityEnabled) {
                                    scope.launch { snackbarHostState.showSnackbar("Aksesibilitas sudah aktif.") }
                                } else {
                                    openAccessibilitySettings()
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
                                val (sw, sh) = screenMetrics()
                                val quick = viewModel.createQuickAutoClicker(sw, sh)
                                AutomationCoordinator.selectMacro(quick)
                                launchOverlay()
                            },
                            onCreateMacro = {
                                val created = viewModel.createNewMacro()
                                editingMacroId = created.id
                            },
                            onImportMacro = {
                                importLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
                            },
                            onEditMacro = { m -> editingMacroId = m.id },
                            onLaunchMacroOverlay = { m ->
                                AutomationCoordinator.selectMacro(m)
                                launchOverlay()
                            },
                            onDuplicateMacro = viewModel::duplicateMacro,
                            onExportMacro = { m ->
                                pendingExportMacro = m
                                val safeName = m.name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "macro" }
                                exportMacroLauncher.launch("$safeName.zip")
                            },
                            onDeleteMacro = { m -> viewModel.deleteMacro(m.id) }
                        )

                        MainTab.IMAGES -> ImageLibraryScreen(
                            images = images,
                            onCropFromScreen = {
                                launchOverlay(FloatingOverlayService.ACTION_OPEN_CROP, addToMacro = false)
                            },
                            onImportFromGallery = {
                                galleryImageLauncher.launch("image/*")
                            },
                            onRecropImage = viewModel::recropImage,
                            onRenameImage = viewModel::renameImage,
                            onDeleteImage = viewModel::deleteImage
                        )

                        MainTab.TOOLS -> Column(modifier = Modifier.fillMaxSize()) {
                            val options = listOf("Lua Studio", "Live Log", "Pengaturan")
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
                                0 -> LuaStudioScreen(
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
                                            scope.launch { snackbarHostState.showSnackbar("Aktifkan MacroGG di Accessibility Settings terlebih dahulu.") }
                                            openAccessibilitySettings()
                                        } else {
                                            val (sw, sh) = screenMetrics()
                                            AutomationCoordinator.runLuaScript(s, sw, sh)
                                        }
                                    }
                                )
                                1 -> LiveLogPanel(logs = liveLogs)
                                2 -> SettingsPanel(
                                    settings = settings,
                                    onUpdateSettings = viewModel::updateSettings,
                                    onExportAllBackup = {
                                        exportBackupLauncher.launch("macrogg_backup.zip")
                                    },
                                    onImportBackup = {
                                        importLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
