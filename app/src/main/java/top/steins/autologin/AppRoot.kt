package top.steins.autologin

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import top.steins.autologin.navigation.AppDestination
import top.steins.autologin.ui.screen.AccountScreen
import top.steins.autologin.ui.screen.HomeScreen
import top.steins.autologin.ui.screen.LogScreen
import top.steins.autologin.ui.screen.SettingsScreen
import top.steins.autologin.ui.screen.WifiConfigScreen

@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val targetWifis by viewModel.settingsRepository.targetWifis.collectAsStateWithLifecycle()
    val navController = rememberNavController()
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

    fun navigateTo(destination: AppDestination) {
        navController.navigate(destination.route) {
            launchSingleTop = true
        }
    }

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
                onNavigateBack = navController::popBackStack,
                onNavigateToLog = { navigateTo(AppDestination.Log) },
                onNavigateToWifiConfig = { navigateTo(AppDestination.WifiConfig) }
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
}
