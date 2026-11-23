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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.automation.AutomationCoordinator
import com.example.automation.ExecutionLogBus
import com.example.automation.FloatingOverlayService
import com.example.automation.ScreenColorSamplerService
import com.example.data.GoogleAuthHelper
import com.example.data.ThemeMode
import com.example.data.UiState
import com.example.ui.AuthScreen
import com.example.ui.DashboardScreen
import com.example.ui.LogsAndAccountScreen
import com.example.ui.LuaStudioScreen
import com.example.ui.MacrosScreen
import com.example.ui.TapLuaViewModel
import com.example.ui.theme.TapLuaTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

private enum class TopDestination(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD(R.string.nav_dashboard, Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    MACROS(R.string.nav_macros, Icons.Filled.TouchApp, Icons.Outlined.TouchApp),
    LUA(R.string.nav_lua, Icons.Filled.Code, Icons.Outlined.Code),
    LOGS(R.string.nav_logs, Icons.Filled.Terminal, Icons.Outlined.Terminal)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TapLuaRootApp()
        }
    }
}

@Composable
private fun TapLuaRootApp() {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    if (currentUser == null) {
        TapLuaTheme(themeMode = ThemeMode.SYSTEM, dynamicColor = true) {
            AuthScreen(
                onAuthSuccess = { currentUser = Firebase.auth.currentUser }
            )
        }
    } else {
        val uid = currentUser!!.uid
        key(uid) {
            AuthenticatedTapLuaShell(
                currentUserId = uid,
                userEmail = currentUser?.email,
                fallbackDisplayName = currentUser?.displayName ?: "Pengguna TapLua"
            )
        }
    }
}

@Composable
private fun AuthenticatedTapLuaShell(
    currentUserId: String,
    userEmail: String?,
    fallbackDisplayName: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: TapLuaViewModel = viewModel(
        key = currentUserId,
        factory = TapLuaViewModel.Factory(context)
    )

    val userProfileState by viewModel.userProfileState.collectAsStateWithLifecycle()
    val macrosState by viewModel.macrosState.collectAsStateWithLifecycle()
    val scriptsState by viewModel.scriptsState.collectAsStateWithLifecycle()
    val runHistoryState by viewModel.runHistoryState.collectAsStateWithLifecycle()
    val permissionHealth by viewModel.permissionHealth.collectAsStateWithLifecycle()
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val syntaxResult by viewModel.syntaxResult.collectAsStateWithLifecycle()

    val engineState by AutomationCoordinator.engineState.collectAsStateWithLifecycle()
    val activeTargetName by AutomationCoordinator.activeTargetName.collectAsStateWithLifecycle()
    val isOverlayActive by AutomationCoordinator.isOverlayActive.collectAsStateWithLifecycle()
    val lastPickedPixel by AutomationCoordinator.lastPickedPixel.collectAsStateWithLifecycle()
    val liveLogs by ExecutionLogBus.logs.collectAsStateWithLifecycle()
    val isColorCapturing by ScreenColorSamplerService.isCapturing.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }

    val userProfile = (userProfileState as? UiState.Success)?.data
    val themeMode = userProfile?.resolvedThemeMode() ?: ThemeMode.SYSTEM
    val displayName = userProfile?.displayName?.ifBlank { fallbackDisplayName } ?: fallbackDisplayName

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenColorSamplerService.start(context, result.resultCode, result.data!!)
            scope.launch { snackbarHostState.showSnackbar("Color Sampler layar diaktifkan.") }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Izin deteksi warna layar dibatalkan.") }
        }
    }

    // Refresh permission states reactively on resume and capture changes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isColorCapturing) {
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
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun requestMediaProjection() {
        val mgr = context.getSystemService(MediaProjectionManager::class.java)
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    fun launchOverlaySafe() {
        if (!Settings.canDrawOverlays(context)) {
            scope.launch { snackbarHostState.showSnackbar("Aktifkan izin 'Tampilkan di atas aplikasi lain' terlebih dahulu.") }
            requestOverlayPermission()
            return
        }
        FloatingOverlayService.start(context)
    }

    fun getScreenMetrics(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context as? Activity)?.windowManager?.defaultDisplay?.getRealMetrics(metrics)
        return (metrics.widthPixels.takeIf { it > 0 } ?: 1080) to (metrics.heightPixels.takeIf { it > 0 } ?: 2400)
    }

    TapLuaTheme(themeMode = themeMode, dynamicColor = dynamicColorEnabled) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpanded = maxWidth >= 600.dp

            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!isExpanded) {
                        NavigationBar(windowInsets = WindowInsets.navigationBars) {
                            TopDestination.entries.forEachIndexed { index, dest ->
                                val selected = selectedTab == index
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { selectedTab = index },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) }
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isExpanded) {
                        NavigationRail {
                            TopDestination.entries.forEachIndexed { index, dest ->
                                val selected = selectedTab == index
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { selectedTab = index },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (TopDestination.entries[selectedTab]) {
                            TopDestination.DASHBOARD -> DashboardScreen(
                                displayName = displayName,
                                permissionHealth = permissionHealth,
                                engineState = engineState,
                                activeTargetName = activeTargetName,
                                isOverlayActive = isOverlayActive,
                                lastPickedPixel = lastPickedPixel,
                                macrosState = macrosState,
                                scriptsState = scriptsState,
                                runHistoryState = runHistoryState,
                                onOpenAccessibilitySettings = ::openAccessibilitySettings,
                                onRequestOverlayPermission = ::requestOverlayPermission,
                                onRequestMediaProjection = ::requestMediaProjection,
                                onStopMediaProjection = { ScreenColorSamplerService.stop(context) },
                                onToggleOverlay = {
                                    if (isOverlayActive) FloatingOverlayService.stop(context)
                                    else launchOverlaySafe()
                                },
                                onStopEngine = { AutomationCoordinator.stop() },
                                onCreateStarterMacro = {
                                    viewModel.createStarterMacro { selectedTab = TopDestination.MACROS.ordinal }
                                },
                                onCreateStarterScript = {
                                    viewModel.createStarterLuaScript { selectedTab = TopDestination.LUA.ordinal }
                                },
                                onNavigateToMacros = { selectedTab = TopDestination.MACROS.ordinal },
                                onNavigateToLua = { selectedTab = TopDestination.LUA.ordinal }
                            )

                            TopDestination.MACROS -> MacrosScreen(
                                isExpandedScreen = isExpanded,
                                macrosState = macrosState,
                                lastPickedPixel = lastPickedPixel,
                                onSaveMacro = viewModel::saveMacro,
                                onDeleteMacro = viewModel::deleteMacro,
                                onLaunchOverlayWithMacro = { macro, steps ->
                                    AutomationCoordinator.selectMacroForRun(macro, steps)
                                    launchOverlaySafe()
                                },
                                onRunMacroNow = { macro, steps ->
                                    if (!permissionHealth.accessibilityEnabled) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Aktifkan TapLua Gesture Engine di Accessibility Settings terlebih dahulu.")
                                        }
                                        openAccessibilitySettings()
                                    } else {
                                        AutomationCoordinator.runVisualMacro(macro, steps)
                                    }
                                }
                            )

                            TopDestination.LUA -> LuaStudioScreen(
                                isExpandedScreen = isExpanded,
                                scriptsState = scriptsState,
                                syntaxResult = syntaxResult,
                                onValidateSyntax = viewModel::validateLuaSyntax,
                                onClearSyntaxResult = viewModel::clearSyntaxResult,
                                onSaveScript = viewModel::saveScript,
                                onDeleteScript = viewModel::deleteScript,
                                onLaunchOverlayWithScript = { script ->
                                    AutomationCoordinator.selectLuaScriptForRun(script)
                                    launchOverlaySafe()
                                },
                                onRunScriptNow = { script ->
                                    if (!permissionHealth.accessibilityEnabled) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Aktifkan TapLua Gesture Engine di Accessibility Settings terlebih dahulu.")
                                        }
                                        openAccessibilitySettings()
                                    } else {
                                        val (w, h) = getScreenMetrics()
                                        AutomationCoordinator.runLuaScript(script, w, h)
                                    }
                                }
                            )

                            TopDestination.LOGS -> LogsAndAccountScreen(
                                liveLogs = liveLogs,
                                runHistoryState = runHistoryState,
                                userProfile = userProfile,
                                userEmail = userEmail,
                                dynamicColorEnabled = dynamicColorEnabled,
                                onDynamicColorChange = viewModel::setDynamicColorEnabled,
                                onSavePreferences = viewModel::saveUserPreferences,
                                onDeleteHistoryEntry = viewModel::deleteRunHistory,
                                onSignOut = {
                                    GoogleAuthHelper.signOut(
                                        credentialManager = credentialManager,
                                        scope = scope,
                                        onBeforeSignOut = {
                                            AutomationCoordinator.stop()
                                            FloatingOverlayService.stop(context)
                                            ScreenColorSamplerService.stop(context)
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
