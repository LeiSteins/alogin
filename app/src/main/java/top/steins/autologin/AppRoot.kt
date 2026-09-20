package top.steins.autologin

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import top.steins.autologin.navigation.AppDestination
import top.steins.autologin.navigation.appComposable
import top.steins.autologin.network.update.UpdateState
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.rememberCapsuleToastState
import top.steins.autologin.ui.screen.AccountScreen
import top.steins.autologin.ui.screen.AboutScreen
import top.steins.autologin.ui.screen.HomeScreen
import top.steins.autologin.ui.screen.LogScreen
import top.steins.autologin.ui.screen.SettingsScreen
import top.steins.autologin.ui.screen.WifiConfigScreen

private val NavigationAnimationSpec = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 800f,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

private val NavigationDimmingAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 800f
)

private const val CoveredPageAlpha = 0.72f

@Composable
fun AppRoot(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val targetWifis by viewModel.targetWifis.collectAsStateWithLifecycle()
    val credentialResetPending by viewModel.credentialResetPending.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
    val httpLogEnabled by viewModel.httpLogEnabled.collectAsStateWithLifecycle()
    val httpLogs by viewModel.httpLogs.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val toastState = rememberCapsuleToastState()
    var dismissedUpdateVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onLocationPermissionChanged()
    }

    LaunchedEffect(uiState.hasLocationPermission) {
        if (!uiState.hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.updateMessages.collect { toastState.show(it) }
    }

    fun navigateTo(destination: AppDestination) {
        navController.navigate(destination.route) {
            launchSingleTop = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = NavigationAnimationSpec,
                    initialOffsetX = { it }
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    animationSpec = NavigationAnimationSpec,
                    targetOffsetX = { -it / 3 }
                ) + fadeOut(
                    animationSpec = NavigationDimmingAnimationSpec,
                    targetAlpha = CoveredPageAlpha
                )
            },
            popEnterTransition = {
                slideInHorizontally(
                    animationSpec = NavigationAnimationSpec,
                    initialOffsetX = { -it / 3 }
                ) + fadeIn(
                    animationSpec = NavigationDimmingAnimationSpec,
                    initialAlpha = CoveredPageAlpha
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = NavigationAnimationSpec,
                    targetOffsetX = { it }
                )
            }
        ) {
            appComposable(AppDestination.Home, navController) { _ ->
                HomeScreen(
                    wifiName = uiState.wifiName,
                    ipAddress = uiState.ipAddress,
                    isOnline = uiState.isOnline,
                    accountOverview = uiState.accountOverview,
                    isAccountInfoLoading = uiState.isAccountInfoLoading,
                    accountInfoError = uiState.accountInfoError,
                    isDeviceListAvailable = uiState.isDeviceListAvailable,
                    canLogoutDevices = uiState.canLogoutDevices,
                    networkStatusError = uiState.networkStatusError,
                    targetWifis = targetWifis,
                    onRefreshAccountInfo = viewModel::refreshAccountInfo,
                    onCheckNetworkStatus = viewModel::refreshStatus,
                    onRetryAccountInfo = viewModel::retryAccountInfo,
                    onLogin = viewModel::login,
                    onConfirmLogin = viewModel::confirmLogin,
                    onLogoutDevice = viewModel::logoutDevice,
                    onRefreshAfterDeviceLogout = viewModel::refreshAfterDeviceLogout,
                    onRefreshAfterIndeterminateDeviceLogout =
                        viewModel::refreshAfterIndeterminateDeviceLogout,
                    onShowToast = { toastState.show(it) },
                    onNavigateToAccount = { navigateTo(AppDestination.Account) },
                    onNavigateToSettings = { navigateTo(AppDestination.Settings) }
                )
            }

            appComposable(AppDestination.Account, navController) { onNavigateBack ->
                AccountScreen(
                    username = username,
                    password = password,
                    onSaveCredentials = viewModel::saveCredentials,
                    onShowToast = { toastState.show(it) },
                    onNavigateBack = onNavigateBack
                )
            }

            appComposable(AppDestination.Settings, navController) { onNavigateBack ->
                SettingsScreen(
                    targetWifiCount = targetWifis.size,
                    appearanceMode = appearanceMode,
                    onAppearanceModeChange = viewModel::saveAppearanceMode,
                    httpLogEnabled = httpLogEnabled,
                    logEntryCount = httpLogs.size,
                    updateState = updateState,
                    onNavigateBack = onNavigateBack,
                    onNavigateToLog = { navigateTo(AppDestination.Log) },
                    onNavigateToWifiConfig = { navigateTo(AppDestination.WifiConfig) },
                    onNavigateToAbout = { navigateTo(AppDestination.About) },
                    onCheckForUpdates = { viewModel.checkForUpdates(manual = true) },
                    onDownloadUpdate = viewModel::downloadAvailableUpdate
                )
            }

            appComposable(AppDestination.About, navController) { onNavigateBack ->
                AboutScreen(
                    httpLogEnabled = httpLogEnabled,
                    onVersionClick = viewModel::onAboutVersionClicked,
                    onShowToast = { toastState.show(it) },
                    onNavigateBack = onNavigateBack
                )
            }

            appComposable(AppDestination.Log, navController) { onNavigateBack ->
                LogScreen(
                    entries = httpLogs,
                    onClearLogs = viewModel::clearHttpLogs,
                    onDisableHttpLog = viewModel::disableHttpLog,
                    onNavigateBack = onNavigateBack
                )
            }

            appComposable(AppDestination.WifiConfig, navController) { onNavigateBack ->
                WifiConfigScreen(
                    targetWifis = targetWifis,
                    onAddTargetWifi = viewModel::addTargetWifi,
                    onRemoveTargetWifi = viewModel::removeTargetWifi,
                    onShowToast = { toastState.show(it) },
                    onNavigateBack = onNavigateBack
                )
            }
        }

        CapsuleToast(
            state = toastState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    val availableUpdate = (updateState as? UpdateState.Available)?.update
    if (availableUpdate != null && dismissedUpdateVersion != availableUpdate.version) {
        AlertDialog(
            onDismissRequest = { dismissedUpdateVersion = availableUpdate.version },
            title = {
                Text(stringResource(R.string.update_dialog_title, availableUpdate.version))
            },
            text = {
                Text(
                    stringResource(
                        R.string.update_dialog_message,
                        BuildConfig.VERSION_NAME
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismissedUpdateVersion = availableUpdate.version
                        viewModel.downloadAvailableUpdate()
                    }
                ) {
                    Text(stringResource(R.string.update_download))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { dismissedUpdateVersion = availableUpdate.version }
                ) {
                    Text(stringResource(R.string.update_later))
                }
            }
        )
    }

    if (credentialResetPending) {
        AlertDialog(
            onDismissRequest = {
                viewModel.acknowledgeCredentialReset()
            },
            title = {
                Text(stringResource(R.string.credential_reset_title))
            },
            text = {
                Text(stringResource(R.string.credential_reset_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.acknowledgeCredentialReset()
                        navigateTo(AppDestination.Account)
                    }
                ) {
                    Text(stringResource(R.string.credential_reset_fill_now))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.acknowledgeCredentialReset()
                    }
                ) {
                    Text(stringResource(R.string.credential_reset_later))
                }
            }
        )
    }
}
