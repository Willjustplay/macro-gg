package com.willjustplay.wjustplayer.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Macro
import com.example.data.MacroSecurity
import com.willjustplay.wjustplayer.data.PlayerStorageHelper
import com.willjustplay.wjustplayer.runtime.PlayerAccessibilityService
import com.willjustplay.wjustplayer.runtime.PlayerCoordinator
import com.willjustplay.wjustplayer.runtime.PlayerOverlayService
import com.willjustplay.wjustplayer.runtime.PlayerSafeLogBus
import com.willjustplay.wjustplayer.runtime.PlayerScreenCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private enum class PlayerTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    MACROS("Macro", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle),
    LOGS("Log", Icons.Filled.Terminal, Icons.Outlined.Terminal)
}

class PlayerMainActivity : ComponentActivity() {

    private val incomingWjmUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        enableEdgeToEdge()
        setContent {
            WjustPlayerApp(
                incomingUriFlow = incomingWjmUri,
                onConsumeIncomingUri = { incomingWjmUri.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { incomingWjmUri.value = it }
        }
    }
}

@Composable
private fun WjustPlayerApp(
    incomingUriFlow: MutableStateFlow<Uri?>,
    onConsumeIncomingUri: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory(context))

    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val macroSets by viewModel.macroSets.collectAsStateWithLifecycle()
    val accountsByKey by viewModel.accountsByKey.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val incomingUri by incomingUriFlow.collectAsStateWithLifecycle()

    val engineState by PlayerCoordinator.engineState.collectAsStateWithLifecycle()
    val isOverlayActive by PlayerCoordinator.isOverlayActive.collectAsStateWithLifecycle()
    val activeMacro by PlayerCoordinator.activeMacro.collectAsStateWithLifecycle()
    val liveSessionStats by PlayerCoordinator.liveSessionStats.collectAsStateWithLifecycle()
    val logs by PlayerSafeLogBus.logs.collectAsStateWithLifecycle()
    val isCapturing by PlayerScreenCaptureService.isCapturing.collectAsStateWithLifecycle()
    val isAccessibilityConnected by PlayerAccessibilityService.isConnected.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var showLocalImportDialog by remember { mutableStateOf(false) }
    var showStatsAndNotifySheet by remember { mutableStateOf(false) }
    var managingAccountsMacro by remember { mutableStateOf<Macro?>(null) }
    var localWjmFiles by remember { mutableStateOf<List<PlayerStorageHelper.LocalWjmEntry>>(emptyList()) }
    var loadingLocalFiles by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            PlayerScreenCaptureService.start(context, result.resultCode, result.data!!)
            scope.launch { snackbarHostState.showSnackbar("Tangkap layar aktif.") }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Izin tangkap layar dibatalkan.") }
        }
    }

    val openWjmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importWjmFromUri(uri)
    }

    val openWjmFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.importWjmFromUri(uri)
    }

    fun openLocalWjmScanner() {
        loadingLocalFiles = true
        showLocalImportDialog = true
        viewModel.loadLocalWjmFiles { list ->
            localWjmFiles = list
            loadingLocalFiles = false
        }
    }

    fun pickWjmFile() {
        val opened = runCatching {
            openWjmLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }.isSuccess
        if (opened) return
        val fallback = runCatching {
            openWjmFallbackLauncher.launch("*/*")
        }.isSuccess
        if (!fallback) {
            openLocalWjmScanner()
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
                scope.launch { snackbarHostState.showSnackbar("Aktifkan izin Overlay di Pengaturan Aplikasi.") }
            }
        }
    }

    fun requestScreenCapture() {
        runCatching {
            val mgr = context.getSystemService(MediaProjectionManager::class.java)
            mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("Gagal membuka izin Tangkap Layar.") }
        }
    }

    fun requestBatteryUnrestricted() {
        if (permissions.batteryOptimizationIgnored) {
            scope.launch { snackbarHostState.showSnackbar("Mode Tetap Aktif sudah menyala.") }
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        }
    }

    fun launchOverlay() {
        if (!Settings.canDrawOverlays(context)) {
            scope.launch { snackbarHostState.showSnackbar("Aktifkan izin Overlay terlebih dahulu.") }
            requestOverlayPermission()
            return
        }
        PlayerOverlayService.start(context)
    }

    fun launchMacro(m: Macro) {
        val parentSet = if (m.setId.isBlank()) null else macroSets.firstOrNull { it.id == m.setId }
        val denial = MacroSecurity.checkDirectRun(m, viewModel.effectiveNowMs(), parentSet)
        if (denial != null) {
            scope.launch { snackbarHostState.showSnackbar(MacroSecurity.denialMessage(m, denial, parentSet)) }
            return
        }
        PlayerCoordinator.selectMacro(m)
        launchOverlay()
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

    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        onConsumeIncomingUri()
        viewModel.importWjmFromUri(uri)
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

    WjustPlayerTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                NavigationBar(
                    windowInsets = WindowInsets.navigationBars,
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    PlayerTab.entries.forEachIndexed { idx, tab ->
                        val selected = selectedTab == idx
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedTab = idx },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Crossfade(
                targetState = PlayerTab.entries[selectedTab],
                animationSpec = tween(durationMillis = 160),
                label = "playerTabCrossfade",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) { tab ->
                when (tab) {
                    PlayerTab.MACROS -> PlayerHomeScreen(
                        macros = macros,
                        macroSets = macroSets,
                        accountsByKey = accountsByKey,
                        permissions = permissions,
                        engineState = engineState,
                        isOverlayActive = isOverlayActive,
                        activeMacroId = activeMacro?.id.orEmpty(),
                        nowMs = viewModel.effectiveNowMs(),
                        resolveLogoPath = viewModel::macroIconPath,
                        accountKeyFor = viewModel::accountKeyFor,
                        onOpenAccessibility = {
                            if (permissions.accessibilityEnabled) {
                                scope.launch { snackbarHostState.showSnackbar("Aksesibilitas sudah aktif.") }
                            } else {
                                showAccessibilityDisclosure = true
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
                                scope.launch { snackbarHostState.showSnackbar("Tangkap layar sudah aktif.") }
                            } else {
                                requestScreenCapture()
                            }
                        },
                        onRequestBatteryUnrestricted = ::requestBatteryUnrestricted,
                        onToggleOverlay = {
                            if (isOverlayActive) PlayerOverlayService.stop(context) else launchOverlay()
                        },
                        onStopEngine = { PlayerCoordinator.stop() },
                        onImportWjm = ::pickWjmFile,
                        onScanLocalDownloads = ::openLocalWjmScanner,
                        onPlayMacro = ::launchMacro,
                        onManageAccounts = { target -> managingAccountsMacro = target },
                        onTogglePassive = viewModel::togglePassive,
                        onDeleteMacro = viewModel::deleteMacro,
                        onDeleteMacroSet = viewModel::deleteMacroSet,
                        onOpenStatsAndNotify = { showStatsAndNotifySheet = true }
                    )
                    PlayerTab.LOGS -> PlayerLogScreen(
                        logs = logs,
                        onClear = { PlayerSafeLogBus.clear() }
                    )
                }
            }
        }

        if (showStatsAndNotifySheet) {
            PlayerStatsAndNotifySheet(
                notifyStore = com.willjustplay.wjustplayer.data.PlayerNotificationStore.get(context),
                liveStats = liveSessionStats,
                onDismiss = { showStatsAndNotifySheet = false }
            )
        }

        if (showAccessibilityDisclosure) {
            PlayerAccessibilityDisclosureDialog(
                onAccept = {
                    showAccessibilityDisclosure = false
                    openAccessibilitySettings()
                },
                onDecline = { showAccessibilityDisclosure = false }
            )
        }

        if (showLocalImportDialog) {
            LocalWjmImportDialog(
                files = localWjmFiles,
                loading = loadingLocalFiles,
                onRefresh = ::openLocalWjmScanner,
                onPickFile = { entry ->
                    showLocalImportDialog = false
                    viewModel.importWjmFromLocalEntry(entry)
                },
                onDismiss = { showLocalImportDialog = false }
            )
        }

        managingAccountsMacro?.let { targetMacro ->
            PlayerAccountManagerDialog(
                macro = targetMacro,
                initialAccounts = viewModel.accountsFor(targetMacro),
                onSave = { updatedAccounts ->
                    viewModel.saveMacroAccounts(targetMacro, updatedAccounts)
                    managingAccountsMacro = null
                },
                onDismiss = { managingAccountsMacro = null }
            )
        }
    }
}
