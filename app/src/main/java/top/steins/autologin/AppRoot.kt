package top.steins.autologin

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import top.steins.autologin.navigation.AppDestination
import top.steins.autologin.network.update.UpdateState
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.rememberCapsuleToastState
import top.steins.autologin.ui.screen.AccountScreen
import top.steins.autologin.ui.screen.HomeScreen
import top.steins.autologin.ui.screen.LogScreen
import top.steins.autologin.ui.screen.SettingsScreen
import top.steins.autologin.ui.screen.WifiConfigScreen

@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val targetWifis by viewModel.settingsRepository.targetWifis.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val updateToastState = rememberCapsuleToastState()
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
        viewModel.updateMessages.collect(updateToastState::show)
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
                .background(MaterialTheme.colorScheme.background),
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it })
            },
            exitTransition = {
                slideOutHorizontally(targetOffsetX = { -it / 3 })
            },
            popEnterTransition = {
                slideInHorizontally(initialOffsetX = { -it / 3 })
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it })
            }
        ) {
            composable(AppDestination.Home.route) {
                HomeScreen(
                    wifiName = uiState.wifiName,
                    ipAddress = uiState.ipAddress,
                    isOnline = uiState.isOnline,
                    accountOverview = uiState.accountOverview,
                    isAccountInfoLoading = uiState.isAccountInfoLoading,
                    accountInfoError = uiState.accountInfoError,
                    networkStatusError = uiState.networkStatusError,
                    targetWifis = targetWifis,
                    onRefreshAccountInfo = viewModel::refreshAccountInfo,
                    onCheckNetworkStatus = viewModel::refreshStatus,
                    onRetryAccountInfo = viewModel::retryAccountInfo,
                    onLogin = viewModel::login,
                    onConfirmLogin = viewModel::confirmLogin,
                    onLogoutDevice = viewModel::logoutDevice,
                    onRefreshAfterDeviceLogout = viewModel::refreshAfterDeviceLogout,
                    onNavigateToAccount = { navigateTo(AppDestination.Account) },
                    onNavigateToSettings = { navigateTo(AppDestination.Settings) }
                )
            }

            composable(AppDestination.Account.route) {
                AccountScreen(
                    settingsRepo = viewModel.settingsRepository,
                    onNavigateBack = navController::popBackStack
                )
            }

            composable(AppDestination.Settings.route) {
                SettingsScreen(
                    settingsRepo = viewModel.settingsRepository,
                    updateState = updateState,
                    onNavigateBack = navController::popBackStack,
                    onNavigateToLog = { navigateTo(AppDestination.Log) },
                    onNavigateToWifiConfig = { navigateTo(AppDestination.WifiConfig) },
                    onCheckForUpdates = { viewModel.checkForUpdates(manual = true) },
                    onDownloadUpdate = viewModel::downloadAvailableUpdate
                )
            }

            composable(AppDestination.Log.route) {
                LogScreen(onNavigateBack = navController::popBackStack)
            }

            composable(AppDestination.WifiConfig.route) {
                WifiConfigScreen(
                    settingsRepo = viewModel.settingsRepository,
                    onNavigateBack = navController::popBackStack
                )
            }
        }

        CapsuleToast(
            state = updateToastState,
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
}
